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
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservedDateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
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
    private RedissonClient redissonClient;

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
        redissonClient.getKeys().flushall();

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
    @DisplayName("동시에 같은 숙소/날짜로 예약 요청을 진행해도 분산락은 하나의 예약만 진행시켜야 한다.")
    void concurrentReservation_shouldAllowOnlyOneSuccess() throws InterruptedException {
        //given
        Long accommodationId = savedAccommodationId;
        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 22);

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger acquired = new AtomicInteger();

        //when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    var daysToReserve = ReservationRequestDto.CreateReservationDto.builder()
                            .checkInDate(checkIn).checkOutDate(checkOut).message("락 검증").build();

                    boolean reserved = reservationFacade.preReserveDates(accommodationId, daysToReserve);
                    if (reserved) acquired.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        // then
        assertThat(acquired.get())
                .as("분산락은 정확히 하나의 예약만 통과시켜야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("동일 숙소에 대해 동일 날짜에 여러 사용자가 예약 진행 시 먼저 예약을 진행한 사용자의 예약이 DB에 등록되어야 한다.")
    void firstAcquirer_ShouldCommitDataSuccessfully() throws InterruptedException {
        // given
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatchForOthers = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Long accommodationId = accommodationRepository.findById(savedAccommodationId).get().getId();
        Long firstMemberId = memberRepository.findAll().get(0).getId();
        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 22);

        ReservationRequestDto.CreateReservationDto dto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(checkIn)
                        .checkOutDate(checkOut)
                        .message("동시성 테스트")
                        .build();

        // when
        executorService.submit(() -> {
            try {
                boolean result = reservationFacade.preReserveDates(accommodationId, dto);
                if (result) {
                    reservationService.createReservation(firstMemberId, accommodationId, dto);
                }
            } finally {
                doneLatch.countDown();
            }
        });

        Thread.sleep(30);

        for (int i = 2; i <= threadCount; i++) {
            final long memberId = i;
            executorService.submit(() -> {
                try {
                    startLatchForOthers.await();
                    boolean result = reservationFacade.preReserveDates(accommodationId, dto);
                    if (result) {
                        reservationService.createReservation(memberId, accommodationId, dto);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatchForOthers.countDown();

        doneLatch.await();
        executorService.shutdown();

        // then
         List<Reservation> allReservations = reservationRepository.findAll();

        // 예약은 반드시 1개여야 하고, 그 주인은 반드시 첫번째로 예약한 사람이어야함
        assertThat(allReservations).hasSize(1);
        assertThat(allReservations.get(0).getGuest().getId()).isEqualTo(firstMemberId);
    }

}
