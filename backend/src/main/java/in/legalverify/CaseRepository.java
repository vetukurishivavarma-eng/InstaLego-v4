package in.legalverify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CaseRepository extends JpaRepository<CaseRecord, UUID> {
}
