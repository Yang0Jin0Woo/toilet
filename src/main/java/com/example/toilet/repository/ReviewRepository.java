package com.example.toilet.repository;

import com.example.toilet.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    @Query("""
           select r
           from Review r
           left join fetch r.user u
           where r.toilet.id = :toiletId
             and (r.blocked = false or r.blocked is null)
           order by r.id desc
           """)
    List<Review> findActiveByToiletIdOrderByIdDesc(Long toiletId);

    interface ToiletRatingAgg {
        Long getToiletId();
        Double getAvg();
        Long getCnt();
    }

    interface SingleRatingAgg {
        Double getAvg();
        Long getCnt();
    }

    interface SingleRatingTotalAgg {
        Long getSum();
        Long getCnt();
    }

    @Query("""
           select r.toilet.id as toiletId, avg(r.rating) as avg, count(r) as cnt
           from Review r
           where r.toilet.id in :toiletIds and (r.blocked = false or r.blocked is null)
           group by r.toilet.id
           """)
    List<ToiletRatingAgg> aggregateByToiletIds(Collection<Long> toiletIds);

    @Query("""
           select avg(r.rating) as avg, count(r) as cnt
           from Review r
           where r.toilet.id = :toiletId and (r.blocked = false or r.blocked is null)
           """)
    SingleRatingAgg aggregateByToiletId(Long toiletId);

    @Query("""
           select coalesce(sum(r.rating), 0) as sum, count(r) as cnt
           from Review r
           where r.toilet.id = :toiletId and (r.blocked = false or r.blocked is null)
           """)
    SingleRatingTotalAgg aggregateTotalsByToiletId(Long toiletId);

    @Modifying
    @Query("""
           update Review r
           set r.reportCount = coalesce(r.reportCount, 0) + 1
           where r.id = :reviewId
           """)
    int incrementReportCount(@Param("reviewId") Long reviewId);

    @Modifying
    @Query("""
           delete from Review r
           where r.id = :reviewId
             and coalesce(r.reportCount, 0) >= :threshold
           """)
    int deleteIfReportCountGte(@Param("reviewId") Long reviewId,
                               @Param("threshold") int threshold);
}
