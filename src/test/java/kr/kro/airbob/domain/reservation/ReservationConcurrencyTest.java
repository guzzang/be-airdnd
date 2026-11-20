package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.reservation.BookedDateCache.ClaimResult;
import kr.kro.airbob.domain.reservation.common.ReservationStatus;
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservedDate;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservedDateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class ReservationConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationFacade reservationFacade;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccommodationRepository accommodationRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private OccupancyPolicyRepository occupancyPolicyRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservedDateRepository reservedDateRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private BookedDateCache bookedDateCache;

    private Long savedAccommodationId;

    // MySQL 컨테이너
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("user")
            .withPassword("pass");

    // Redis 컨테이너
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2.0")
            .withExposedPorts(6379);

    // 동적으로 Spring 속성 등록
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // MySQL
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        // Flyway
        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        // DB 초기화
        reservedDateRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        accommodationRepository.deleteAllInBatch();
        occupancyPolicyRepository.deleteAllInBatch();
        addressRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
        redisTemplate.execute((RedisCallback<Void>) (RedisConnection connection) -> {
            connection.serverCommands().flushAll();
            return null;
        });

        // 1. 10명의 회원 등록
        for (long i = 1; i <= 10; i++) {
            Member member = Member.builder()
                    .email("user" + i + "@test.com")
                    .password("hashed-password")
                    .nickname("유저" + i)
                    .role(MemberRole.MEMBER)
                    .thumbnailImageUrl("https://example.com/profile" + i + ".jpg")
                    .build();
            memberRepository.save(member);
        }

        // 2. 숙소에 필요한 Address, OccupancyPolicy 저장
        Address address = Address.builder()
                .country("대한민국")
                .city("서울특별시")
                .district("종로구")
                .street("세종대로")
                .detail("101호")
                .postalCode("1536")
                .latitude(37.5665)
                .longitude(126.9780)
                .build();
        addressRepository.save(address);

        OccupancyPolicy policy = OccupancyPolicy.builder()
                .maxOccupancy(4)
                .adultOccupancy(2)
                .childOccupancy(1)
                .infantOccupancy(1)
                .petOccupancy(0)
                .build();
        occupancyPolicyRepository.save(policy);

        // 3. 숙소 생성
        Member host = memberRepository.findAll().get(0); // 첫 번째 멤버를 호스트로
        Accommodation accommodation = Accommodation.builder()
                        .name("테스트 숙소")
                        .description("편안한 숙소입니다")
                        .basePrice(10000)
                        .thumbnailUrl("https://example.com/thumb.jpg")
                        .type(AccommodationType.APARTMENT)
                        .address(address)
                        .occupancyPolicy(policy)
                        .member(host)
                        .build();
        Accommodation savedAccommodation = accommodationRepository.save(accommodation);
        savedAccommodationId = savedAccommodation.getId();
    }

    @Test
    @DisplayName("동시에 같은 숙소/날짜로 예약 요청을 진행해도 하나의 예약만 진행되어야 한다.")
    void concurrentReservation_shouldAllowOnlyOneSuccess() throws InterruptedException {
        //given
        Long accommodationId = savedAccommodationId;
        LocalDate checkIn = LocalDate.now().plusMonths(1);
        LocalDate checkOut = checkIn.plusDays(2);

        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger acquired = new AtomicInteger();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

        //when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    var daysToReserve = ReservationRequestDto.CreateReservationDto.builder()
                            .checkInDate(checkIn).checkOutDate(checkOut).message("동시성 테스트").build();

                    boolean reserved = reservationFacade.preReserveDates(accommodationId, daysToReserve);
                    if (reserved) acquired.incrementAndGet();
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        // then
        assertThat(errors)
                .as("예외 없이 성공 또는 실패로 끝나야 한다")
                .isEmpty();
        assertThat(acquired.get())
                .as("정확히 하나의 예약만 통과해야 한다")
                .isEqualTo(1);
        assertThat(reservedDateRepository.findAll())
                .as("DB에는 2박만 중복 없이 저장되어야 한다")
                .extracting(ReservedDate::getReservedAt)
                .containsExactlyInAnyOrder(checkIn, checkIn.plusDays(1));
    }

    @Test
    @DisplayName("Redis가 동작하지 않아도 DB UNIQUE 제약이 중복 예약을 최종적으로 막아야 한다.")
    void dbUnique_blocksDuplicate_evenWithoutLock() throws InterruptedException {
        Long accommodationId = savedAccommodationId;
        LocalDate checkIn = LocalDate.now().plusMonths(1);
        LocalDate checkOut = checkIn.plusDays(2);
        long daysToReserve = ChronoUnit.DAYS.between(checkIn, checkOut);

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    var dto = ReservationRequestDto.CreateReservationDto.builder()
                            .checkInDate(checkIn).checkOutDate(checkOut).message("unique 검증").build();

                    reservationService.createPreReservation(accommodationId, dto, daysToReserve);
                    success.incrementAndGet();

                } catch (Exception e) {
                    blocked.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        // then
        assertThat(success.get())
                .as("Redis 동작 없이도 DB UNIQUE 제약으로 하나의 예약만 저장에 성공해야한다.")
                .isEqualTo(1);
        assertThat(blocked.get())
                .as("나머지는 UNIQUE 제약 위반으로 차단되어야 한다.")
                .isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("DB에 이미 예약된 날짜가 있으면, 캐시가 비어 있어도 DB에서 예약 날짜를 가져오므로 BOOKED가 반환되어야 한다.")
    void tryClaim_loadsExistingReservationsFromDb() {
        // given
        Accommodation accommodation = accommodationRepository.findById(savedAccommodationId).orElseThrow();
        LocalDate checkIn = LocalDate.now().plusMonths(1);
        LocalDate checkOut = checkIn.plusDays(2);
        LocalDate reservedInDb = checkIn.plusDays(1);

        reservedDateRepository.save(ReservedDate.builder()
                .reservedAt(reservedInDb)
                .status(ReservationStatus.COMPLETED)
                .accommodation(accommodation)
                .build());

        // when
        ClaimResult result = bookedDateCache.tryClaim(savedAccommodationId, checkIn, checkOut);

        // then
        String key = "booked:accommodation:" + savedAccommodationId;
        assertThat(result)
                .as("DB에 있는 예약 날짜와 겹치면 BOOKED여야 한다")
                .isEqualTo(ClaimResult.BOOKED);
        assertThat(redisTemplate.opsForSet().members(key))
                .as("Redis에는 DB에서 가져온 예약 날짜만 있어야 한다")
                .containsExactlyInAnyOrder("_loaded", reservedInDb.toString());
    }

}
