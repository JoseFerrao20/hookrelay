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

  @Column(nullable = false, length = 100)
  private String secret;

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
    this.id = UUID.randomUUID();
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
