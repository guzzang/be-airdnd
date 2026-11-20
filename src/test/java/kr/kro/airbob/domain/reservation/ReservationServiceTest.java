package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.reservation.common.ReservationStatus;
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservedDate;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservedDateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReservationServiceTest {

    @Mock
    private BookedDateCache bookedDateCache;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AccommodationRepository accommodationRepository;

    @Mock
    private ReservedDateRepository reservedDateRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("예약하려는 날짜에 DB 예약이 있으면 임시 예약이 실패하고 아무것도 저장하지 않는다.")
    void createPreReservation_alreadyReserved() {
        // given
        Long accommodationId = 1L;
        LocalDate checkIn = LocalDate.of(2028, 5, 1);
        LocalDate checkOut = LocalDate.of(2028, 5, 4);

        ReservationRequestDto.CreateReservationDto dto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(checkIn)
                        .checkOutDate(checkOut)
                        .build();

        given(reservedDateRepository.findReservedDates(accommodationId, checkIn, checkOut))
                .willReturn(List.of(mock(ReservedDate.class)));

        // when
        boolean result = reservationService.createPreReservation(accommodationId, dto, 3);

        // then
        assertThat(result).isFalse();
        verify(reservedDateRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("예약하려는 날짜가 비어 있으면 숙박일 수만큼 PENDING 날짜를 저장한다.")
    void createPreReservation_success_savesEveryNight() {
        // given
        Long accommodationId = 1L;
        LocalDate checkIn = LocalDate.of(2028, 5, 1);
        LocalDate checkOut = LocalDate.of(2028, 5, 4);

        ReservationRequestDto.CreateReservationDto dto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(checkIn)
                        .checkOutDate(checkOut)
                        .build();

        given(reservedDateRepository.findReservedDates(accommodationId, checkIn, checkOut))
                .willReturn(Collections.emptyList());
        given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(mock(Accommodation.class)));

        // when
        boolean result = reservationService.createPreReservation(accommodationId, dto, 3);

        // then
        assertThat(result).isTrue();
        ArgumentCaptor<List<ReservedDate>> captor = ArgumentCaptor.forClass(List.class);
        verify(reservedDateRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(ReservedDate::getReservedAt)
                .containsExactly(checkIn, checkIn.plusDays(1), checkIn.plusDays(2));
        assertThat(captor.getValue())
                .extracting(ReservedDate::getStatus)
                .containsOnly(ReservationStatus.PENDING);
    }

    @Test
    void createReservation_shouldSaveReservation_andUpdateReservedDatesToCompleted() {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;
        Long reservationId = 1L;

        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 22); // 2박

        ReservationRequestDto.CreateReservationDto dto = ReservationRequestDto.CreateReservationDto.builder()
                .checkInDate(checkIn)
                .checkOutDate(checkOut)
                .message("테스트 예약")
                .build();

        Member guest = mock(Member.class);
        Accommodation accommodation = mock(Accommodation.class);
        Reservation reservation = mock(Reservation.class);

        // stub basePrice와 reservation ID
        given(accommodation.getBasePrice()).willReturn(10000);
        given(reservation.getId()).willReturn(reservationId);

        // repository stub 설정
        given(memberRepository.findById(memberId)).willReturn(Optional.of(guest));
        given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
        given(reservationRepository.save(any())).willReturn(reservation);

        // 예약된 날짜(PENDING) 상태 미리 존재
        ReservedDate reservedDate1 = spy(ReservedDate.builder()
                .reservedAt(checkIn)
                .status(ReservationStatus.PENDING)
                .accommodation(accommodation)
                .build());

        ReservedDate reservedDate2 = spy(ReservedDate.builder()
                .reservedAt(checkIn.plusDays(1))
                .status(ReservationStatus.PENDING)
                .accommodation(accommodation)
                .build());

        given(reservedDateRepository.findReservedDates(accommodationId, checkIn, checkOut))
                .willReturn(List.of(reservedDate1, reservedDate2));

        // when
        Long result = reservationService.createReservation(memberId, accommodationId, dto);

        // then
        assertThat(result).isEqualTo(reservationId);
        assertThat(reservedDate1.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(reservedDate2.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
    }


    @Test
    @DisplayName("예약을 취소하면 Redis 선점을 되돌린 뒤 예약 날짜와 예약을 삭제한다.")
    void cancelReservation_releasesCacheThenDeletes() {
        // given
        Long reservationId = 1L;
        Long accommodationId = 1L;
        LocalDate checkIn = LocalDate.of(2028, 5, 1);
        LocalDate checkOut = LocalDate.of(2028, 5, 3);

        Accommodation accommodation = mock(Accommodation.class);
        Reservation reservation = mock(Reservation.class);
        given(accommodation.getId()).willReturn(accommodationId);
        given(reservation.getAccommodation()).willReturn(accommodation);
        given(reservation.getCheckIn()).willReturn(checkIn.atTime(15, 0));
        given(reservation.getCheckOut()).willReturn(checkOut.atTime(11, 0));
        given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));

        // when
        reservationService.cancelReservation(reservationId);

        // then
        InOrder inOrder = inOrder(bookedDateCache, reservedDateRepository, reservationRepository);
        inOrder.verify(bookedDateCache).release(accommodationId, checkIn, checkOut);
        inOrder.verify(reservedDateRepository).deleteReservedDates(accommodationId, checkIn, checkOut);
        inOrder.verify(reservationRepository).delete(reservation);
    }

    @Test
    @DisplayName("존재하지 않는 유저가 예약하려고 하면 예외가 발생해야 한다.")
    void createReservation_shouldThrowException_whenMemberNotFound()  {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;
        ReservationRequestDto.CreateReservationDto request = mock(ReservationRequestDto.CreateReservationDto.class);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(memberId, accommodationId, request))
                .isInstanceOf(MemberNotFoundException.class)
                .hasMessage("존재하지 않는 사용자입니다.");
    }

    @Test
    @DisplayName("존재하지 않는 숙소를 예약하려고 하면 예외가 발생해야 한다.")
    void createReservation_shouldThrowException_whenAccommodationNotFound()  {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;
        ReservationRequestDto.CreateReservationDto request = mock(ReservationRequestDto.CreateReservationDto.class);

        Member member = mock(Member.class);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(member));

        given(accommodationRepository.findById(accommodationId))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(memberId, accommodationId, request))
                .isInstanceOf(AccommodationNotFoundException.class)
                .hasMessage("존재하지 않는 숙소입니다.");
    }



}
