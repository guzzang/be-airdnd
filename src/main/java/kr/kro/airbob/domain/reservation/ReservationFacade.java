package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationFacade {

    private final RedissonClient redissonClient;
    private final ReservationService reservationService;

    public boolean preReserveDates(Long accommodationId, ReservationRequestDto.CreateReservationDto createReservationDto) {
        long daysToReserve = ChronoUnit.DAYS.between(createReservationDto.getCheckInDate(), createReservationDto.getCheckOutDate());
        List<RLock> acquiredLockKeys = new ArrayList<>();

        for (int n = 0; n < daysToReserve; n++) {
            LocalDate dayToReserve = createReservationDto.getCheckInDate().plusDays(n);
            String lockKey = "lock:accommodation:" + accommodationId + ":dayToReserve:" + dayToReserve;
            acquiredLockKeys.add(redissonClient.getLock(lockKey));
        }

        RLock multiLock = redissonClient.getMultiLock(acquiredLockKeys.toArray(new RLock[0]));

        try {
            boolean available = multiLock.tryLock(5, 20, TimeUnit.SECONDS);

            if (!available) {
                return false; // 락 획득 실패
            }

            log.info("[{}] 락 획득 성공", Thread.currentThread().getName());

            return reservationService.createPreReservation(accommodationId, createReservationDto, daysToReserve);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트 발생");
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
                log.info("[{}] 락 반납 완료", Thread.currentThread().getName());
            }
        }
    }
}