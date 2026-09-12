package verwaltungsassistent.web.planning.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaCaseViewClaimRepository extends JpaRepository<CaseViewClaimEntity, String> {

    List<CaseViewClaimEntity> findByExpiresAtBefore(Instant cutoff);

    @Modifying
    @Query("delete from CaseViewClaimEntity c where c.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
