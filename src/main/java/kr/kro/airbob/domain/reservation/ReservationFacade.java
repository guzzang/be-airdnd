package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationFacade {

    private final ReservationService reservationService;
    private final BookedDateCache bookedDateCache;

    public boolean preReserveDates(Long accommodationId, ReservationRequestDto.CreateReservationDto createReservationDto) {
        LocalDate checkIn = createReservationDto.getCheckInDate();
        LocalDate checkOut = createReservationDto.getCheckOutDate();

        long daysToReserve = ChronoUnit.DAYS.between(checkIn, checkOut);

        if (bookedDateCache.tryClaim(accommodationId, checkIn, checkOut) == BookedDateCache.ClaimResult.BOOKED) {
            return false;
        }

        try {
            boolean reserved = reservationService.createPreReservation(accommodationId, createReservationDto, daysToReserve);
            if (!reserved) releaseQuietly(accommodationId, checkIn, checkOut);
            return reserved;
        } catch (DataIntegrityViolationException e) {
            releaseQuietly(accommodationId, checkIn, checkOut);
            return false;
        } catch (RuntimeException e) {
            releaseQuietly(accommodationId, checkIn, checkOut);
            throw e;
        }
    }

    private void releaseQuietly(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
        try {
            bookedDateCache.release(accommodationId, checkIn, checkOut);
        } catch (Exception e) {
            log.warn("예약 날짜 캐시 되돌리기 실패 accommodationId={}, {}~{}", accommodationId, checkIn, checkOut, e);
        }
    }
}
