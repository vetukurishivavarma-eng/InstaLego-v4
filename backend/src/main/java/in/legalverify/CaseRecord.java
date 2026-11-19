package in.legalverify;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A verification case. The random UUID is the access token: there are no user accounts in the MVP. */
@Entity
@Table(name = "cases")
public class CaseRecord {
    @Id
    public UUID id = UUID.randomUUID();
    public Instant createdAt = Instant.now();
    public boolean paid;
    public String orderId;
    @Column(columnDefinition = "text")
    public String analysisJson;
}
