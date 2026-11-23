package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.reservation.BookedDateCache.ClaimResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BookedDateCacheTest {

    private static final Long ACCOMMODATION_ID = 999_999L;
    private static final String KEY = "booked:accommodation:" + ACCOMMODATION_ID;

    private static final LocalDate MAY_1 = LocalDate.of(2028, 5, 1);
    private static final LocalDate MAY_3 = LocalDate.of(2028, 5, 3);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("user")
            .withPassword("pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2.0")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("search.enabled", () -> "false");
    }

    @Autowired
    private BookedDateCache bookedDateCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("캐시가 비어 있으면 DB에서 채운 뒤 선점해야 한다.")
    void tryClaim_loadsWhenEmpty() {
        ClaimResult result = bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3);

        assertThat(result).isEqualTo(ClaimResult.CLAIMED);
        assertThat(redisTemplate.opsForSet().members(KEY))
                .containsExactlyInAnyOrder("_loaded", "2028-05-01", "2028-05-02");
        assertThat(redisTemplate.getExpire(KEY)).isPositive();
    }

    @Test
    @DisplayName("예약된 날짜를 다시 선점하려고 하면 BOOKED가 반환되어야 한다.")
    void tryClaim_sameDates_booked() {
        bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3);

        assertThat(bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3)).isEqualTo(ClaimResult.BOOKED);
    }

    @Test
    @DisplayName("예약 요청 날짜 중 하루라도 겹치면 BOOKED가 반환되어야 하고, 아무 날짜도 추가되지 않아야 한다.")
    void tryClaim_overlap_booked_withoutPartialWrite() {
        bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3);

        ClaimResult result = bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_3.minusDays(1), MAY_3.plusDays(2));

        assertThat(result).isEqualTo(ClaimResult.BOOKED);
        assertThat(redisTemplate.opsForSet().isMember(KEY, "2028-05-03")).isFalse();
    }

    @Test
    @DisplayName("체크아웃 날과 체크인 날이 맞닿은 예약은 CLAIMED가 반환되어야 한다.")
    void tryClaim_adjacent_claimed() {
        bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3);

        assertThat(bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_3, MAY_3.plusDays(2))).isEqualTo(ClaimResult.CLAIMED);
    }

    @Test
    @DisplayName("release 하면 같은 날짜를 다시 선점할 수 있어야 한다.")
    void release_thenClaimAgain() {
        bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3);

        bookedDateCache.release(ACCOMMODATION_ID, MAY_1, MAY_3);

        assertThat(redisTemplate.opsForSet().isMember(KEY, "_loaded")).isTrue();
        assertThat(bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3)).isEqualTo(ClaimResult.CLAIMED);
    }

    @Test
    @DisplayName("100개 스레드가 빈 캐시에서 동시에 같은 날짜를 선점해도 CLAIMED는 정확히 1개가 반환되어야 한다.")
    void tryClaim_concurrent_onlyOneClaimed() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        Queue<ClaimResult> results = new ConcurrentLinkedQueue<>();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(bookedDateCache.tryClaim(ACCOMMODATION_ID, MAY_1, MAY_3));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threadCount);
        assertThat(results).filteredOn(r -> r == ClaimResult.CLAIMED).hasSize(1);
    }
}
