package com.nocobase.auth.oidc;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OidcLoginCodeRepository extends JpaRepository<OidcLoginCodeEntity, java.util.UUID> {

    Optional<OidcLoginCodeEntity> findByCodeHash(String codeHash);

    /** Atomically claims an unconsumed, unexpired code. Only one concurrent caller can return 1. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE oidc_login_code
               SET consumed_at = NOW()
             WHERE code_hash = :codeHash
               AND consumed_at IS NULL
               AND expires_at > NOW()
            """, nativeQuery = true)
    int consume(@Param("codeHash") String codeHash);
}
