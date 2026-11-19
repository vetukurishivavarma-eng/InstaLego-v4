package in.legalverify;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "case_documents", uniqueConstraints = @UniqueConstraint(columnNames = {"caseId", "sha256"}))
public class CaseDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public UUID caseId;
    public String fileName;
    public String tag;
    public String sha256;
    @Column(columnDefinition = "text")
    public String factsJson;
}
