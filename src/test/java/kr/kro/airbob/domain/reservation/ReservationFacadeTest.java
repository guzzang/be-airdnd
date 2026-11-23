package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.reservation.BookedDateCache.ClaimResult;
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReservationFacadeTest {

    @Mock
    private ReservationService reservationService;

    @Mock
    private BookedDateCache bookedDateCache;

    @InjectMocks
    private ReservationFacade reservationFacade;

    private final Long accommodationId = 1L;
    private final LocalDate checkIn = LocalDate.of(2028, 5, 1);
    private final LocalDate checkOut = LocalDate.of(2028, 5, 3);
    private ReservationRequestDto.CreateReservationDto dto;

    @BeforeEach
    void setUp() {
        dto = ReservationRequestDto.CreateReservationDto.builder()
                .checkInDate(checkIn)
                .checkOutDate(checkOut)
                .build();
    }

    @Test
    @DisplayName("Redis 선점 결과가 BOOKED면 DB에 접근하지 않고 바로 실패해야 한다.")
    void preReserveDates_booked() {
        given(bookedDateCache.tryClaim(accommodationId, checkIn, checkOut)).willReturn(ClaimResult.BOOKED);

        boolean result = reservationFacade.preReserveDates(accommodationId, dto);

        assertThat(result).isFalse();
        verifyNoInteractions(reservationService);
        verify(bookedDateCache, never()).release(accommodationId, checkIn, checkOut);
    }

    @Test
    @DisplayName("선점에 성공하고 DB 저장도 성공하면 예약에 성공하고 선점을 유지해야한다.")
    void preReserveDates_success() {
        given(bookedDateCache.tryClaim(accommodationId, checkIn, checkOut)).willReturn(ClaimResult.CLAIMED);
        given(reservationService.createPreReservation(accommodationId, dto, 2L)).willReturn(true);

        boolean result = reservationFacade.preReserveDates(accommodationId, dto);

        assertThat(result).isTrue();
        verify(bookedDateCache, never()).release(accommodationId, checkIn, checkOut);
    }

    @Test
    @DisplayName("선점에 성공했지만 DB에 이미 예약이 있으면 실패하고 선점을 되돌려야 한다.")
    void preReserveDates_dbAlreadyReserved() {
        given(bookedDateCache.tryClaim(accommodationId, checkIn, checkOut)).willReturn(ClaimResult.CLAIMED);
        given(reservationService.createPreReservation(accommodationId, dto, 2L)).willReturn(false);

        boolean result = reservationFacade.preReserveDates(accommodationId, dto);

        assertThat(result).isFalse();
        verify(bookedDateCache).release(accommodationId, checkIn, checkOut);
    }

    @Test
    @DisplayName("DB unique 제약에 걸리면 예외 대신 실패를 반환하고 선점을 되돌려야 한다.")
    void preReserveDates_uniqueViolation() {
        given(bookedDateCache.tryClaim(accommodationId, checkIn, checkOut)).willReturn(ClaimResult.CLAIMED);
        given(reservationService.createPreReservation(accommodationId, dto, 2L))
                .willThrow(new DataIntegrityViolationException("duplicate"));

        boolean result = reservationFacade.preReserveDates(accommodationId, dto);

        assertThat(result).isFalse();
        verify(bookedDateCache).release(accommodationId, checkIn, checkOut);
    }

    @Test
    @DisplayName("그 밖의 DB 오류는 선점을 되돌린 뒤 원래 예외를 그대로 던져야 한다.")
    void preReserveDates_unexpectedError() {
        RuntimeException dbError = new IllegalStateException("db down");
        given(bookedDateCache.tryClaim(accommodationId, checkIn, checkOut)).willReturn(ClaimResult.CLAIMED);
        given(reservationService.createPreReservation(accommodationId, dto, 2L)).willThrow(dbError);

        assertThatThrownBy(() -> reservationFacade.preReserveDates(accommodationId, dto)).isSameAs(dbError);
        verify(bookedDateCache).release(accommodationId, checkIn, checkOut);
    }

    @Test
    @DisplayName("선점 되돌리기(release)가 실패해도 원래 예외가 묻히지 않아야 한다.")
    void preReserveDates_releaseFails_keepsOriginalError() {
        RuntimeException dbError = new IllegalStateException("db down");
        given(bookedDateCache.tryClaim(accommodationId, checkIn, checkOut)).willReturn(ClaimResult.CLAIMED);
        given(reservationService.createPreReservation(accommodationId, dto, 2L)).willThrow(dbError);
        willThrow(new IllegalStateException("redis down")).given(bookedDateCache).release(accommodationId, checkIn, checkOut);

        assertThatThrownBy(() -> reservationFacade.preReserveDates(accommodationId, dto)).isSameAs(dbError);
    }

    @Test
    @DisplayName("선점 되돌리기(release)가 실패해도 실패 응답(false)은 그대로 유지되어야 한다.")
    void preReserveDates_releaseFails_keepsFalse() {
        given(bookedDateCache.tryClaim(accommodationId, checkIn, checkOut)).willReturn(ClaimResult.CLAIMED);
        given(reservationService.createPreReservation(accommodationId, dto, 2L)).willReturn(false);
        willThrow(new IllegalStateException("redis down")).given(bookedDateCache).release(accommodationId, checkIn, checkOut);

        boolean result = reservationFacade.preReserveDates(accommodationId, dto);

        assertThat(result).isFalse();
    }
}
