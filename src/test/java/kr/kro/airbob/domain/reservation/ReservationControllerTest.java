package kr.kro.airbob.domain.reservation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kr.kro.airbob.domain.common.BaseControllerDocumentationTest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureRestDocs
@ExtendWith(RestDocumentationExtension.class)
@WebMvcTest(ReservationController.class)
public class ReservationControllerTest extends BaseControllerDocumentationTest {

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private ReservationFacade reservationFacade;

    @Autowired
    private ReservationController reservationController;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup(RestDocumentationContextProvider restDocumentation) {
        reservationController = new ReservationController(reservationService, reservationFacade);
        mockMvc = MockMvcBuilders.standaloneSetup(reservationController)
                .apply(documentationConfiguration(restDocumentation))
                .build();
    }

    @Test
    @DisplayName("예약이 등록되면 status code 201을 반환해야 한다.")
    void createReservation() throws Exception {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;
        Long createdReservationId = 1L;

        ReservationRequestDto.CreateReservationDto requestDto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(LocalDate.of(2025, 6, 20))
                        .checkOutDate(LocalDate.of(2025, 6, 22))
                        .message("창문 있는 방 부탁드립니다.")
                        .build();


        given(reservationFacade.preReserveDates(eq(accommodationId), any())).willReturn(true);
        given(reservationService.createReservation(eq(memberId), eq(accommodationId), any())).willReturn(createdReservationId);

        // when & then
        mockMvc.perform(post("/api/reservations/accommodations/{accommodationId}", accommodationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJsonString(requestDto))
                        .requestAttr("memberId", memberId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(createdReservationId))
                .andDo(document("reservation-create",
                        pathParameters(
                                parameterWithName("accommodationId").description("예약할 숙소 ID")
                        ),
                        requestFields(
                                fieldWithPath("checkInDate").description("체크인 날짜 및 시간"),
                                fieldWithPath("checkOutDate").description("체크아웃 날짜 및 시간"),
                                fieldWithPath("message").description("호스트에게 전달할 메시지").optional()
                        ),
                        responseFields(
                                fieldWithPath("id").description("생성된 예약 ID")
                        )
                ));
    }

    @Test
    @DisplayName("예약이 실패하면 status code 400을 반환해야 한다.")
    void createReservation_fail() throws Exception {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;

        ReservationRequestDto.CreateReservationDto requestDto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(LocalDate.of(2025, 6, 20))
                        .checkOutDate(LocalDate.of(2025, 6, 22))
                        .message("창문 있는 방 부탁드립니다.")
                        .build();

        given(reservationFacade.preReserveDates(eq(accommodationId), any()))
                .willReturn(false); // 예약 불가

        // when & then
        mockMvc.perform(post("/api/reservations/accommodations/{accommodationId}", accommodationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJsonString(requestDto))
                        .requestAttr("memberId", memberId))
                .andExpect(status().isBadRequest());
    }


    private static String asJsonString(Object obj) throws JsonProcessingException {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule()) // LocalDate 지원
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) // timestamp 아님
                .setDateFormat(new StdDateFormat().withColonInTimeZone(false)) // 표준 ISO 포맷
                .writeValueAsString(obj);
    }

    @Override
    protected Object getController() {
        return reservationController;
    }

}
