package io.github.joseferrao20.hookrelay.endpoint;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "endpoints")
public class Endpoint {

  @Id private UUID id;

  @Column(nullable = false, length = 2048)
  private String url;

  @Column(length = 500)
  private String description;

  /** Ciphertext ({@code v1:...}), see SecretCipher. */
  @Column(nullable = false, length = 255)
  private String secret;

  /** Ciphertext of the secret replaced by the last rotation, while its grace period lasts. */
  @Column(name = "previous_secret", length = 255)
  private String previousSecret;

  @Column(name = "previous_secret_expires_at")
  private Instant previousSecretExpiresAt;

  @Column(nullable = false)
  private boolean enabled;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "endpoint_event_types", joinColumns = @JoinColumn(name = "endpoint_id"))
  @Column(name = "event_type", nullable = false, length = 100)
  private Set<String> eventTypes = new HashSet<>();

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected Endpoint() {}

  public Endpoint(
      String url,
      String description,
      String secret,
      boolean enabled,
      Set<String> eventTypes,
      Instant now) {
    this(UUID.randomUUID(), url, description, secret, enabled, eventTypes, now);
  }

  /** {@code secret} is the stored (encrypted) form; the id is needed first as encryption AAD. */
  public Endpoint(
      UUID id,
      String url,
      String description,
      String secret,
      boolean enabled,
      Set<String> eventTypes,
      Instant now) {
    this.id = id;
    this.url = url;
    this.description = description;
    this.secret = secret;
    this.enabled = enabled;
    this.eventTypes = new HashSet<>(eventTypes);
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getSecret() {
    return secret;
  }

  public String getPreviousSecret() {
    return previousSecret;
  }

  public Instant getPreviousSecretExpiresAt() {
    return previousSecretExpiresAt;
  }

  /** Replaces the secret; {@code previous} may be null with a null expiry (no grace period). */
  public void rotateSecret(String newSecret, String previous, Instant previousExpiresAt) {
    this.secret = newSecret;
    this.previousSecret = previous;
    this.previousSecretExpiresAt = previousExpiresAt;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Set<String> getEventTypes() {
    return eventTypes;
  }

  public void replaceEventTypes(Set<String> types) {
    this.eventTypes.clear();
    this.eventTypes.addAll(types);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch(Instant now) {
    this.updatedAt = now;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void markDeleted(Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
  }
}
