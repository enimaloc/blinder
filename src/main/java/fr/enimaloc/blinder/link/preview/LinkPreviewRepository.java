package fr.enimaloc.blinder.link.preview;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LinkPreviewRepository extends JpaRepository<LinkPreview, UUID> {

    @Query("select p from LinkPreview p where p.link.id = :linkId")
    Optional<LinkPreview> findByLinkId(@Param("linkId") UUID linkId);
}
