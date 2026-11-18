package kr.kro.airbob.domain.reservation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookedDateCache {

    private static final String KEY_PREFIX = "booked:accommodation:";
    private static final String LOADED = "_loaded";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public boolean isAnyBooked(Long accommodationId, LocalDate checkIn, LocalDate checkOut){
        List<String> nightsToReserve = makeDatesToReserveList(checkIn, checkOut);

        Object[] nightsToReserveToCheck = new Object[nightsToReserve.size() + 1];
        nightsToReserveToCheck[0] = LOADED;
        for (int i = 0; i < nightsToReserve.size(); i++) {
            nightsToReserveToCheck[i + 1] = nightsToReserve.get(i);
        }

        Map<Object, Boolean> result = redisTemplate.opsForSet().isMember(makeRedisKey(accommodationId), nightsToReserveToCheck);

        if (!Boolean.TRUE.equals(result.get(LOADED))) {
            Set<String> booked = load(accommodationId);
            return nightsToReserve.stream().anyMatch(booked::contains);
        }

        return nightsToReserve.stream().anyMatch(night -> Boolean.TRUE.equals(result.get(night)));
    }

    public void add(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
        redisTemplate.opsForSet().add(makeRedisKey(accommodationId), makeDatesToReserveList(checkIn, checkOut).toArray(new String[0]));
    }

    public void remove(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
        redisTemplate.opsForSet().remove(makeRedisKey(accommodationId), makeDatesToReserveList(checkIn, checkOut).toArray());
    }

    private Set<String> load(Long accommodationId) {
        Set<String> booked = jdbcTemplate.queryForList(
                        "SELECT reserved_at FROM reserved_dates WHERE accommodation_id = ? AND reserved_at >= ?",
                        LocalDate.class, accommodationId, LocalDate.now())
                .stream()
                .map(LocalDate::toString)
                .collect(Collectors.toSet());                             // ① DB에서 날짜 읽기 (Step 1)

        List<String> nightsReserved = new ArrayList<>(booked);
        nightsReserved.add(LOADED);                                               // ② 날짜들 + _loaded
        redisTemplate.opsForSet().add(makeRedisKey(accommodationId), nightsReserved.toArray(new String[0]));  // ③ SADD
        redisTemplate.expire(makeRedisKey(accommodationId), TTL);                   // ④ EXPIRE 300
        return booked;                                                     // ⑤ 판단용으로 반환
    }

    private List<String> makeDatesToReserveList(LocalDate checkIn, LocalDate checkOut) {
        return checkIn.datesUntil(checkOut).map(LocalDate::toString).toList();
    }

    private String makeRedisKey(Long accommodationId) {
        return KEY_PREFIX + accommodationId;     // "booked:accommodation:1"
    }



}
