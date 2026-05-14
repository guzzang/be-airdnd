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
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservedDateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.ReservationChangedEvent;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservedDateRepository reservedDateRepository;
    private final AccommodationRepository accommodationRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public boolean createPreReservation(Long accommodationId, ReservationRequestDto.CreateReservationDto createReservationDto, long daysToReserve){
        // DB에 실제 예약된 날짜가 있는지 확인
        List<ReservedDate> alreadyReservedDates = reservedDateRepository.findReservedDates(
                accommodationId, createReservationDto.getCheckInDate(), createReservationDto.getCheckOutDate());

        if (!alreadyReservedDates.isEmpty()) {
            return false; // 예약 불가
        }

        log.info("[{}] 예약 진행", Thread.currentThread().getName());

        // checkin checkout 날짜에 대해 예약 처리 (임시 예약 상태로 처리)
        List<ReservedDate> preReservedDates = new ArrayList<>();

        Accommodation accommodation = accommodationRepository.findById(accommodationId)
                .orElseThrow(AccommodationNotFoundException::new);

        for (int n = 0; n < daysToReserve - 1; n++) {
            ReservedDate reservedDate = ReservedDate.builder()
                    .reservedAt(createReservationDto.getCheckInDate().plusDays(n))
                    .status(ReservationStatus.PENDING)
                    .accommodation(accommodation)
                    .build();
            preReservedDates.add(reservedDate);
        }
        reservedDateRepository.saveAll(preReservedDates);

        return true;
    }


    @Transactional
    public Long createReservation(Long memberId, Long accommodationId, ReservationRequestDto.CreateReservationDto createReservationDto) {
        Member guest = memberRepository.findById(memberId)
                .orElseThrow(MemberNotFoundException::new);

        Accommodation accommodation = accommodationRepository.findById(accommodationId)
                .orElseThrow(AccommodationNotFoundException::new);

        long totalReservationDays = ChronoUnit.DAYS.between(
                createReservationDto.getCheckInDate(),
                createReservationDto.getCheckOutDate()
        );

        long totalPrice = totalReservationDays * accommodation.getBasePrice();

        //1. 예약 확정 저장
        Reservation savedReservation = reservationRepository.save(Reservation.createReservation(createReservationDto, accommodation, guest, (int) totalPrice));

        // 2. ReservedDate 상태 업데이트
        List<ReservedDate> reservedDates = reservedDateRepository.findReservedDates(
                accommodationId, createReservationDto.getCheckInDate(), createReservationDto.getCheckOutDate());

        for (ReservedDate reservedDate : reservedDates) {
            reservedDate.completeReservation();
        }

        eventPublisher.publishEvent(new ReservationChangedEvent(accommodationId));

        return savedReservation.getId();
    }

    @Transactional
    public void cancelReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(ReservationNotFoundException::new);

        reservedDateRepository.deleteReservedDates(reservation.getAccommodation().getId(),
                LocalDate.of(reservation.getCheckIn().getYear(), reservation.getCheckIn().getMonth(), reservation.getCheckIn().getDayOfMonth()),
                LocalDate.of(reservation.getCheckOut().getYear(), reservation.getCheckOut().getMonth(), reservation.getCheckOut().getDayOfMonth()));

        reservationRepository.delete(reservation);

        eventPublisher.publishEvent(new ReservationChangedEvent(reservation.getAccommodation().getId()));
    }
}
