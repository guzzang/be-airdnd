package kr.kro.airbob.domain.reservation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookedDateCache {

    private static final String KEY_PREFIX = "booked:accommodation:";
    private static final String LOADED = "_loaded";
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final String CLAIMED = "claimed";
    private static final String BOOKED = "booked";
    private static final String NOT_LOADED = "not_loaded";

    private static final DefaultRedisScript<String> TRY_CLAIM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[1], '_loaded') == 0 then
                return 'not_loaded'
            end
            for i = 1, #ARGV do
                if redis.call('SISMEMBER', KEYS[1], ARGV[i]) == 1 then
                    return 'booked'
                end
            end
            redis.call('SADD', KEYS[1], unpack(ARGV))
            return 'claimed'
            """, String.class);

    public enum ClaimResult { CLAIMED, BOOKED }

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public ClaimResult tryClaim(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
        String key = makeRedisKey(accommodationId);
        Object[] nights = makeDatesToReserveList(checkIn, checkOut).toArray();

        String result = redisTemplate.execute(TRY_CLAIM_SCRIPT, List.of(key), nights);
        if (NOT_LOADED.equals(result)) {
            load(accommodationId);
            result = redisTemplate.execute(TRY_CLAIM_SCRIPT, List.of(key), nights);
        }

        if (CLAIMED.equals(result)) return ClaimResult.CLAIMED;
        if (BOOKED.equals(result)) return ClaimResult.BOOKED;

        throw new IllegalStateException("tryClaim 실패: accommodationId=" + accommodationId + ", result=" + result);
    }

    public void release(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
        redisTemplate.opsForSet().remove(makeRedisKey(accommodationId), makeDatesToReserveList(checkIn, checkOut).toArray());
    }

    private void load(Long accommodationId) {
        Set<String> booked = jdbcTemplate.queryForList(
                        "SELECT reserved_at FROM reserved_dates WHERE accommodation_id = ? AND reserved_at >= ?",
                        LocalDate.class, accommodationId, LocalDate.now())
                .stream()
                .map(LocalDate::toString)
                .collect(Collectors.toSet());

        List<String> nightsReserved = new ArrayList<>(booked);
        nightsReserved.add(LOADED);
        redisTemplate.opsForSet().add(makeRedisKey(accommodationId), nightsReserved.toArray(new String[0]));
        redisTemplate.expire(makeRedisKey(accommodationId), TTL);
    }

    private List<String> makeDatesToReserveList(LocalDate checkIn, LocalDate checkOut) {
        return checkIn.datesUntil(checkOut).map(LocalDate::toString).toList();
    }

    private String makeRedisKey(Long accommodationId) {
        return KEY_PREFIX + accommodationId;
    }
}
