package kr.kro.airbob.cursor.util;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import kr.kro.airbob.cursor.dto.CursorResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CursorPageInfoCreator {

	private final CursorEncoder cursorEncoder;

	public <T> CursorResponse.PageInfo createPageInfo(
		List<T> content,
		boolean hasNext,
		Function<T, Long> idExtractor,
		Function<T, LocalDateTime> createdAtExtractor) {

		if (content.isEmpty()) {
			return CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(0)
				.build();
		}

		String nextCursor = null;
		if (hasNext) {
			T lastEntity = content.get(content.size()-1);
			CursorResponse.CursorData cursorData = CursorResponse.CursorData.builder()
				.id(idExtractor.apply(lastEntity))
				.lastCreatedAt(createdAtExtractor.apply(lastEntity))
				.build();
			nextCursor = cursorEncoder.encode(cursorData);
		}

		return CursorResponse.PageInfo.builder()
			.hasNext(hasNext)
			.nextCursor(nextCursor)
			.currentSize(content.size())
			.build();
	}

	// 리뷰용
	public <T> CursorResponse.PageInfo createPageInfo(
		List<T> content,
		boolean hasNext,
		Function<T, Long> idExtractor,
		Function<T, LocalDateTime> createdAtExtractor,
		Function<T, Integer> ratingExtractor) {

		if (content.isEmpty()) {
			return CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(0)
				.build();
		}

		String nextCursor = null;
		if (hasNext) {
			T lastEntity = content.get(content.size()-1);
			CursorResponse.ReviewCursorData reviewCursorData = new CursorResponse.ReviewCursorData(
				idExtractor.apply(lastEntity),
				createdAtExtractor.apply(lastEntity),
				ratingExtractor.apply(lastEntity)
			);
			nextCursor = cursorEncoder.encode(reviewCursorData);
		}

		return CursorResponse.PageInfo.builder()
			.hasNext(hasNext)
			.nextCursor(nextCursor)
			.currentSize(content.size())
			.build();
	}
}
