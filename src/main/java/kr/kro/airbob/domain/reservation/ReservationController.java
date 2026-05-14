package kr.kro.airbob.domain.reservation;

import jakarta.servlet.http.HttpServletRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations/accommodations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationFacade reservationFacade;

    @PostMapping("/{accommodationId}")
    public ResponseEntity<Map<String,Long>> createReservation(
            @PathVariable Long accommodationId,
            @RequestBody ReservationRequestDto.CreateReservationDto createReservationDto,
            HttpServletRequest request) {
        //todo 커스텀 에러 생성
        Long memberId = (Long) request.getAttribute("memberId");

        if(!reservationFacade.preReserveDates(accommodationId, createReservationDto)){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        Long reservationId = reservationService.createReservation(memberId, accommodationId, createReservationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", reservationId));
    }

    @DeleteMapping("/{reservationId}")
    public void cancelReservation(@PathVariable Long reservationId) {
        reservationService.cancelReservation(reservationId);
    }

}
