package kr.kro.airbob.search.repository;

import kr.kro.airbob.search.document.AccommodationDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface AccommodationSearchRepository extends ElasticsearchRepository<AccommodationDocument, Long> {

}
