package net.es.nsi.dds.lib.client;

import com.google.common.base.Strings;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.Optional;
import java.util.stream.Collectors;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.bind.JAXBException;
import net.es.nsi.common.constants.Nsi;
import net.es.nsi.common.util.UrlHelper;
import net.es.nsi.dds.lib.dao.SecureType;
import net.es.nsi.dds.lib.jaxb.DdsParser;
import net.es.nsi.dds.lib.jaxb.dds.DocumentEventType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentListType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;
import net.es.nsi.dds.lib.jaxb.dds.ErrorType;
import net.es.nsi.dds.lib.jaxb.dds.FilterCriteriaType;
import net.es.nsi.dds.lib.jaxb.dds.FilterType;
import net.es.nsi.dds.lib.jaxb.dds.ObjectFactory;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionListType;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionRequestType;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionType;
import org.apache.hc.client5.http.utils.DateUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HTTP client for DDS document and subscription operations.
 *
 * <p>Methods consume and close their responses, returning status and payload in
 * result wrappers. Successful reads use {@code OK}, subscription creation uses
 * {@code CREATED}, and subscription deletion uses {@code NO_CONTENT}. Invalid
 * required XML and transport failures generally become {@code INTERNAL_SERVER_ERROR};
 * {@link #delete(String)} and bulk {@link #unsubscribe(String, String, String)}
 * retain their legacy {@code BAD_REQUEST} exception/result behavior. Unknown HTTP
 * status codes can produce a null status. Target construction in several methods
 * occurs before request exception handling, so invalid URL arguments may throw
 * directly. Successful entity reads use Last-Modified or the current time rounded
 * down to seconds. Methods neither verify document signatures nor establish
 * resource authorization. Close this client when finished.
 *
 * @author hacksaw
 */
public class DdsClient extends RestClient {
  private final Logger LOG = LogManager.getLogger(getClass());
  private final ObjectFactory FACTORY = new ObjectFactory();

  /**
   * Creates a DDS client with default pool limits and connector TLS settings.
   *
   * @throws KeyStoreException if a configured store type cannot be loaded
   * @throws IOException if a configured store cannot be read
   * @throws NoSuchAlgorithmException if a required TLS or key algorithm is unavailable
   * @throws CertificateException if a store contains invalid certificates
   * @throws KeyManagementException if TLS context initialization fails
   * @throws UnrecoverableKeyException if a private key cannot be recovered
   * @throws NoSuchProviderException retained by the TLS configuration API
   */
  public DdsClient() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException,
          KeyManagementException, UnrecoverableKeyException, NoSuchProviderException {
    super();
  }

  /**
   * Creates a DDS client with explicit connection limits.
   *
   * @param maxConnPerRoute maximum connections per destination route
   * @param maxConnTotal maximum total pooled connections
   * @throws KeyStoreException if a configured store type cannot be loaded
   * @throws IOException if a configured store cannot be read
   * @throws NoSuchAlgorithmException if a required TLS or key algorithm is unavailable
   * @throws CertificateException if a store contains invalid certificates
   * @throws KeyManagementException if TLS context initialization fails
   * @throws UnrecoverableKeyException if a private key cannot be recovered
   * @throws NoSuchProviderException retained by the TLS configuration API
   */
  public DdsClient(int maxConnPerRoute, int maxConnTotal) throws KeyStoreException, IOException,
          NoSuchAlgorithmException, CertificateException, KeyManagementException, UnrecoverableKeyException,
          NoSuchProviderException {

    super(maxConnPerRoute, maxConnTotal);
  }

  /**
   * Creates a DDS client with explicit connection limits.
   *
   * @param maxConnPerRoute maximum connections per destination route
   * @param maxConnTotal maximum total pooled connections
   * @param secure mutable store configuration and hostname-verification mode;
   *               null selects the connector defaults
   * @throws KeyStoreException if a configured store type cannot be loaded
   * @throws IOException if a configured store cannot be read
   * @throws NoSuchAlgorithmException if a required TLS or key algorithm is unavailable
   * @throws CertificateException if a store contains invalid certificates
   * @throws KeyManagementException if TLS context initialization fails
   * @throws UnrecoverableKeyException if a private key cannot be recovered
   * @throws NoSuchProviderException retained by the TLS configuration API
   */
  public DdsClient(int maxConnPerRoute, int maxConnTotal, SecureType secure) throws KeyStoreException, IOException,
          NoSuchAlgorithmException, CertificateException, KeyManagementException, UnrecoverableKeyException,
          NoSuchProviderException {

    super(maxConnPerRoute, maxConnTotal, secure);
  }

  /**
   * Calls the service's {@code ping} resource without reading an entity.
   *
   * @param baseURL DDS service base URL
   * @return remote status with an initially empty document list, or
   *         {@code INTERNAL_SERVER_ERROR} if the request fails
   */
  public DocumentsResult getPing(String baseURL) {
    DocumentsResult result = new DocumentsResult();
    Response response = null;
    try {
      response = this.get().target(baseURL).path("ping").request(Nsi.NSI_DDS_V1_XML).get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
    } catch (Exception ex) {
      LOG.error("[DdsClient] GET failed for href={}, ex={}", baseURL + "/" + "ping", ex);
      result.setStatus(Status.INTERNAL_SERVER_ERROR);
    } finally {
      if (response != null) {
        response.close();
      }
    }

    return result;
  }

  /**
   * Retrieves all documents from the service's {@code documents} resource.
   *
   * @param baseURL DDS service base URL
   * @return {@code OK} with documents on success; remote status or
   *         {@code INTERNAL_SERVER_ERROR} on response/transport failure
   */
  public DocumentsResult getDocuments(String baseURL) {
    WebTarget webTarget = this.get().target(baseURL).path("documents");

    return get(webTarget);
  }

  /**
   * Retrieves documents using the {@code type} query parameter.
   *
   * @param baseURL DDS service base URL
   * @param type document type filter
   * @return document-list result with the same status contract as {@link #getDocuments(String)}
   */
  public DocumentsResult getDocumentsByType(String baseURL, String type) {
    WebTarget webTarget = this.get().target(baseURL).path("documents").queryParam("type", type);
    return get(webTarget);
  }

  /**
   * Retrieves documents under the service's NSA-specific document path.
   *
   * @param baseURL DDS service base URL
   * @param nsa NSA identifier appended to the document collection path
   * @return document-list result with the same status contract as {@link #getDocuments(String)}
   */
  public DocumentsResult getDocumentsByNsa(String baseURL, String nsa) {
    WebTarget webTarget = this.get().target(baseURL).path("documents").path(nsa);
    return get(webTarget);
  }

  /**
   * Retrieves one document by NSA, type, and document identifier.
   * A successful response uses its Last-Modified header, or the current time at
   * second precision when the header is absent. Target construction happens
   * before request exception handling, so invalid target arguments may throw.
   *
   * @param baseURL DDS service base URL
   * @param nsa NSA identifier path component
   * @param type document type path component
   * @param id document identifier path component
   * @return {@code OK} with a document on success; remote status or
   *         {@code INTERNAL_SERVER_ERROR} on response/transport failure
   */
  public DocumentResult getDocument(String baseURL, String nsa, String type, String id) {
    WebTarget webTarget = this.get().target(baseURL).path("documents").path(nsa).path(type).path(id);

    DocumentResult result = new DocumentResult();
    result.setStatus(Status.BAD_REQUEST);

    Optional<Response> optional = Optional.empty();
    try {
      optional = Optional.of(webTarget.request(Nsi.NSI_DDS_V1_XML).get());

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.OK.getStatusCode()) {
        result.setLastModified(response.getLastModified() == null ?
                (System.currentTimeMillis() / 1000) * 1000 : response.getLastModified().getTime());
        result.setDocument(response.readEntity(DocumentType.class));
      } else {
        LOG.error("DdsClient] Failed to retrieve document = {}, result = {}",
                webTarget.getUri().toASCIIString(), response.getStatusInfo().getReasonPhrase());
        Optional<ErrorType> error = Optional.ofNullable(response.readEntity(ErrorType.class));
        if (error.isPresent()) {
          LOG.error("[DdsClient] GET failed for href={}, error={}.", webTarget.getUri().toASCIIString(), error.get().getLabel());
        }
      }
    } catch (Exception ex) {
      LOG.error("[DdsClient] GET failed for href={}, ex={}", webTarget.getUri().toASCIIString(), ex);
      result.setStatus(Status.INTERNAL_SERVER_ERROR);
    } finally {
      optional.ifPresent(r -> r.close());
    }

    return result;
  }

  private DocumentsResult get(WebTarget webTarget) {
    DocumentsResult result = new DocumentsResult();
    result.setStatus(Status.BAD_REQUEST);

    Optional<Response> optional = Optional.empty();
    try {
      optional = Optional.of(webTarget.request(Nsi.NSI_DDS_V1_XML).get());

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.OK.getStatusCode()) {
        result.setLastModified(response.getLastModified() == null ?
                (System.currentTimeMillis() / 1000) * 1000 : response.getLastModified().getTime());
        result.setDocuments(response.readEntity(DocumentListType.class)
                .getDocument()
                .stream()
                .collect(Collectors.toList()));
      } else {
        LOG.error("[DdsClient] Failed to retrieve list of documents = {}, result = {}",
                webTarget.getUri().toASCIIString(), response.getStatusInfo().getReasonPhrase());
        Optional<ErrorType> error = Optional.ofNullable(response.readEntity(ErrorType.class));
        if (error.isPresent()) {
          LOG.error("[DdsClient] GET failed for href={}, error={}.", webTarget.getUri().toASCIIString(), error.get().getLabel());
        }
      }
    } catch (Exception ex) {
      LOG.error("[DdsClient] GET failed for href={}, ex={}", webTarget.getUri().toASCIIString(), ex);
      result.setStatus(Status.INTERNAL_SERVER_ERROR);
    } finally {
      optional.ifPresent(r -> r.close());
    }

    return result;
  }

  /**
   * Get a list of subscriptions registered on the remote DDS server.
   *
   * @param baseURL DDS service base URL
   * @return {@code OK} with a subscription list on success; remote status or
   *         {@code INTERNAL_SERVER_ERROR} on response/transport failure
   */
  public SubscriptionsResult getSubscriptions(String baseURL) {
    return getSubscriptions(baseURL, null);
  }

  /**
   * Get a list of DDS subscriptions for the specified NSA.
   *
   * @param baseURL DDS service base URL
   * @param nsaId requester identifier, or null/empty to retrieve all subscriptions
   * @return {@code OK} with a subscription list on success; remote status or
   *         {@code INTERNAL_SERVER_ERROR} on response/transport failure
   */
  public SubscriptionsResult getSubscriptions(String baseURL, String nsaId) {
    SubscriptionsResult result = new SubscriptionsResult();
    result.setStatus(Status.BAD_REQUEST);

    WebTarget webTarget = this.get().target(baseURL).path("subscriptions");
    if (!Strings.isNullOrEmpty(nsaId)) {
      webTarget = webTarget.queryParam("requesterId", nsaId);
    }

    Optional<Response> optional = Optional.empty();
    try {
      optional = Optional.of(webTarget.request(Nsi.NSI_DDS_V1_XML).get());

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.OK.getStatusCode()) {
        result.setLastModified(response.getLastModified() == null ?
                (System.currentTimeMillis() / 1000) * 1000 : response.getLastModified().getTime());
        result.setSubscriptions(response.readEntity(SubscriptionListType.class)
                .getSubscription()
                .stream()
                .collect(Collectors.toList()));
      } else {
        LOG.error("DdsClient] Failed to retrieve list of subscriptions {}, result = {}",
                baseURL, response.getStatusInfo().getReasonPhrase());
        Optional<ErrorType> error = Optional.ofNullable(response.readEntity(ErrorType.class));
        if (error.isPresent()) {
          LOG.error("[DdsClient] Subscription get failed, href={}, error={}.", webTarget.getUri().toASCIIString(), error.get().getLabel());
        } else {
          LOG.error("[DdsClient] Subscription get failed, href={}, reason={}.", webTarget.getUri().toASCIIString(), response.getStatusInfo().getReasonPhrase());
        }
      }
    } catch (Exception ex) {
      LOG.error("[DdsClient] GET failed for href={}, ex={}", webTarget.getUri().toASCIIString(), ex);
      result.setStatus(Status.INTERNAL_SERVER_ERROR);
    } finally {
      optional.ifPresent(r -> r.close());
    }

    return result;
  }

  /**
   * Get subscription associated with <b>url</b>.
   *
   * @param baseURL DDS service base URL for relative references
   * @param url absolute or relative reference to the remote subscription
   * @return {@code OK} with a subscription on success; remote status or
   *         {@code INTERNAL_SERVER_ERROR} on response/transport failure
   */
  public SubscriptionResult getSubscription(String baseURL, String url) {
    return getSubscription(baseURL, url, null);
  }

  /**
   * Get subscription associated with <b>url</b>.
   *
   * @param baseURL DDS service base URL for relative references
   * @param url absolute or relative reference to the remote subscription
   * @param lastModified optional conditional-request timestamp; null omits the header
   * @return result with {@code OK} and a subscription, or {@code NOT_MODIFIED}
   *         without a payload; failures retain a remote status or become
   *         {@code INTERNAL_SERVER_ERROR}. Conditional headers use GMT and second
   *         precision; result timestamps are epoch milliseconds
   */
  public SubscriptionResult getSubscription(String baseURL, String url, Date lastModified) {
    SubscriptionResult result = new SubscriptionResult();
    result.setStatus(Status.BAD_REQUEST);

    // Build an absolute URL if the subscription URL is relative.
    WebTarget webTarget;
    if (UrlHelper.isAbsolute(url)) {
      webTarget = this.get().target(url);
    } else {
      webTarget = this.get().target(baseURL).path(url);
    }

    Optional<Response> optional = Optional.empty();
    try {
      if (lastModified != null) {
        optional = Optional.of(webTarget.request(Nsi.NSI_DDS_V1_XML).header("If-Modified-Since",
                DateUtils.formatStandardDate(lastModified.toInstant())).get());
      } else {
        optional = Optional.of(webTarget.request(Nsi.NSI_DDS_V1_XML).get());
      }

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.OK.getStatusCode()) {
        result.setLastModified(response.getLastModified() == null ?
                (System.currentTimeMillis() / 1000) * 1000 : response.getLastModified().getTime());
        result.setSubscription(response.readEntity(SubscriptionType.class));
      } else if (response.getStatus() == Status.NOT_MODIFIED.getStatusCode()) {
        if (response.getLastModified() != null) {
          result.setLastModified(response.getLastModified().getTime());
        } else if (lastModified != null) {
          result.setLastModified((lastModified.getTime() / 1000) * 1000);
        }
      } else {
        LOG.error("DdsClient] Failed to retrieve subscription {}, result = {}",
                url, response.getStatusInfo().getReasonPhrase());
        Optional<ErrorType> error = Optional.ofNullable(response.readEntity(ErrorType.class));
        if (error.isPresent()) {
          LOG.error("[DdsClient] Subscription get failed, href={}, error={}.", webTarget.getUri().toASCIIString(), error.get().getLabel());
        } else {
          LOG.error("[DdsClient] Subscription get failed, href={}, reason={}.", webTarget.getUri().toASCIIString(), response.getStatusInfo().getReasonPhrase());
        }
      }
    } catch (Exception ex) {
      LOG.error("[DdsClient] GET failed for href={}, ex={}", webTarget.getUri().toASCIIString(), ex);
      result.setStatus(Status.INTERNAL_SERVER_ERROR);
    } finally {
      optional.ifPresent(r -> r.close());
    }

    return result;
  }

  /**
   * Subscribes to all document events using a chunked, gzip-compressed XML request.
   *
   * @param baseURL DDS service base URL
   * @param nsaId The requester NSA identifier for this client.
   * @param callbackURL callback endpoint for asynchronous document delivery
   * @return result with {@code CREATED} and the subscription on success;
   *         {@code BAD_REQUEST} if request serialization fails, or
   *         {@code INTERNAL_SERVER_ERROR} if request/response processing fails
   */
  public SubscriptionResult subscribe(String baseURL, String nsaId, String callbackURL) {
    SubscriptionResult result = new SubscriptionResult();
    result.setStatus(Status.BAD_REQUEST);

    // We will register for all events on all documents.
    FilterCriteriaType criteria = FACTORY.createFilterCriteriaType();
    criteria.getEvent().add(DocumentEventType.ALL);
    FilterType filter = FACTORY.createFilterType();
    filter.getInclude().add(criteria);
    SubscriptionRequestType request = FACTORY.createSubscriptionRequestType();
    request.setFilter(filter);
    request.setRequesterId(nsaId);
    request.setCallback(callbackURL);

    // Encode the subscription request.
    String encoded;
    try {
      encoded = DdsParser.getInstance().subscriptionRequest2Xml(request);
    } catch (JAXBException | IOException ex) {
      LOG.error("[DdsClient] error rencoding subscription request on endpoint {}", baseURL, ex);
      return result;
    }

    WebTarget webTarget = this.get().target(baseURL).path("subscriptions");

    Optional<Response> optional = Optional.empty();
    try {
      optional = Optional.of(webTarget
              .request(Nsi.NSI_DDS_V1_XML)
              .post(Entity.entity(encoded, new Variant(MediaType.valueOf(Nsi.NSI_DDS_V1_XML),
                      (java.util.Locale) null, "gzip"))));

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.CREATED.getStatusCode()) {
        result.setLastModified(response.getLastModified() == null ?
                (System.currentTimeMillis() / 1000) * 1000 : response.getLastModified().getTime());
        result.setSubscription(response.readEntity(SubscriptionType.class));
      } else {
        LOG.error("[DdsClient] failed to create subscription {}, result = {}",
                baseURL, response.getStatusInfo().getReasonPhrase());

        Optional<ErrorType> error = Optional.ofNullable(response.readEntity(ErrorType.class));
        if (error.isPresent()) {
          LOG.error("[DdsClient] Add of subscription failed, id={}, label={}, resource={}, description={}",
                  error.get().getId(), error.get().getLabel(), error.get().getResource(),
                  error.get().getDescription());
        } else {
          LOG.error("[DdsClient] Add of subscription failed, endpoint={}, reason={}",
                  baseURL, response.getStatusInfo().getReasonPhrase());
        }
      }
    } catch (Exception ex) {
      LOG.error("[DdsClient] error subscribing on endpoint {}", baseURL, ex);
      result.setStatus(Status.INTERNAL_SERVER_ERROR);
    } finally {
      optional.ifPresent(r -> r.close());
    }

    return result;
  }

  /**
   * Unsubscribes the listed DDS subscriptions for {@code nsaId}, except the
   * identifier to retain, after validating every selected deletion target.
   * The list request and deletions do not follow redirects. Every selected
   * {@code href} must resolve to the caller-selected service origin and exactly
   * its {@code subscriptions/{id}} resource. Origin comparison ignores scheme
   * and host case and treats omitted default ports as equivalent; resource
   * identity is case-sensitive. Relative references, including a leading slash,
   * append to the service base path rather than replacing it.
   *
   * <p>The list response is closed before target validation and deletion. An
   * invalid base, entry, or selected link prevents all deletions. Unsupported
   * forms include userinfo, query strings, fragments, traversal, matrix
   * parameters, and encoded or double-encoded separators. IDs need not be UUIDs;
   * unambiguous Unicode IDs and paths are encoded for dispatch without Unicode
   * normalization. Encoding preserves the validated code-point sequence, so
   * normalization-distinct IDs remain distinct. Ambiguous IDs containing
   * whitespace, percent signs, or URI path delimiters are rejected.
   * No subscription object is rewritten to contain the canonical dispatch URI.
   *
   * <p>Validation confines destinations; it does not establish authorization for
   * the resources listed by the peer. Once deletion starts, failures do not roll
   * back earlier requests, and individual outcomes are not propagated. The
   * direct URL overload retains its caller-selected destination behavior.
   *
   * @param baseURL hierarchical HTTP(S) DDS service base URL, without userinfo,
   *                query, or fragment; deployment path prefixes are preserved
   * @param nsaId requester identifier whose subscriptions should be examined
   * @param id non-null subscription identifier to retain, compared without case;
   *           null returns without making a request
   * @return result whose legacy status remains {@code BAD_REQUEST}; subscriptions
   *         are the selected entries in list order after processing, or null if
   *         listing or validation fails
   */
  public SubscriptionsResult unsubscribe(String baseURL, String nsaId, String id) {
    SubscriptionsResult result = new SubscriptionsResult();
    result.setStatus(Status.BAD_REQUEST);

    try {
      if (id == null) {
        throw new IllegalArgumentException("Missing retained subscription identifier");
      }
      SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy(baseURL);
      List<SubscriptionType> subscriptions;
      WebTarget listTarget = this.get().target(policy.listTarget())
              .queryParam("requesterId", nsaId);
      try (Response response = listTarget.request(Nsi.NSI_DDS_V1_XML)
              .property(ClientProperties.FOLLOW_REDIRECTS, false).get()) {
        if (response.getStatus() != Status.OK.getStatusCode()) {
          LOG.error("[DdsClient] Bulk subscription list failed, status={}", response.getStatus());
          return result;
        }
        subscriptions = new ArrayList<>(response.readEntity(SubscriptionListType.class).getSubscription());
      }

      List<SubscriptionType> selected = new ArrayList<>();
      List<URI> targets = new ArrayList<>();
      for (SubscriptionType subscription : subscriptions) {
        if (subscription == null || subscription.getId() == null || subscription.getId().isBlank()) {
          throw new IllegalArgumentException("Invalid bulk subscription entry");
        }
        if (id.equalsIgnoreCase(subscription.getId())) {
          continue;
        }
        targets.add(policy.deletionTarget(subscription.getId(), subscription.getHref()));
        selected.add(subscription);
      }
      for (URI target : targets) {
        unsubscribe(this.get().target(target), true);
      }
      result.setSubscriptions(selected);
    } catch (Exception ex) {
      // Never log remote hrefs, entry identifiers, or exception messages here.
      LOG.error("[DdsClient] Bulk unsubscribe failed ({})", ex.getClass().getSimpleName());
    }

    return result;
  }

  /**
   * Unsubscribes a subscription at the caller-selected URL. Unlike bulk
   * unsubscribe, this method does not constrain the destination to the service
   * origin or subscription collection and retains the client's redirect policy.
   *
   * @param baseURL DDS service base URL for relative references
   * @param url absolute or relative reference to the subscription to delete
   * @return {@code NO_CONTENT} without a subscription payload on success; remote
   *         status or {@code INTERNAL_SERVER_ERROR} on response/transport failure
   */

  public SubscriptionResult unsubscribe(String baseURL, String url) {
    // Build an absolute URL if the subscription URL is relative.
    WebTarget webTarget;
    if (UrlHelper.isAbsolute(url)) {
      webTarget = this.get().target(url);
    } else {
      webTarget = this.get().target(baseURL).path(url);
    }

    return unsubscribe(webTarget, false);
  }

  private SubscriptionResult unsubscribe(WebTarget webTarget, boolean suppressRedirects) {
    SubscriptionResult result = new SubscriptionResult();
    result.setStatus(Status.BAD_REQUEST);
    String url = webTarget.getUri().toASCIIString();
    Optional<Response> optional = Optional.empty();
    try {
      jakarta.ws.rs.client.Invocation.Builder request = webTarget.request(Nsi.NSI_DDS_V1_XML);
      if (suppressRedirects) {
        request.property(ClientProperties.FOLLOW_REDIRECTS, false);
      }
      optional = Optional.of(request.delete());

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.NO_CONTENT.getStatusCode()) {
        result.setLastModified(response.getLastModified() == null ?
                (System.currentTimeMillis() / 1000) * 1000 : response.getLastModified().getTime());
      } else {
        LOG.error("[DdsClient] failed to delete subscription {}, result = {}",
                url, response.getStatusInfo().getReasonPhrase());

        Optional<ErrorType> error = Optional.ofNullable(response.readEntity(ErrorType.class));
        if (error.isPresent()) {
          LOG.error("[DdsClient] Delete of subscription failed, id={}, label={}, resource={}, description={}",
                  error.get().getId(), error.get().getLabel(), error.get().getResource(),
                  error.get().getDescription());
        } else {
          LOG.error("[DdsClient] Delete of subscription failed, endpoint={}, reason={}",
                  url, response.getStatusInfo().getReasonPhrase());
        }
      }
    } catch (Exception ex) {
      LOG.error("[DdsClient] DELETE failed for href={}, ex={}", url, ex);
      result.setStatus(Status.INTERNAL_SERVER_ERROR);
    }
    finally {
      optional.ifPresent(r -> r.close());
    }

    return result;
  }

  /**
   * Deletes the resource at the caller-selected URL without applying the bulk
   * subscription target policy. The client's redirect policy remains in effect.
   *
   * @param url absolute resource URL
   * @return remote status, or {@code BAD_REQUEST} if the request throws
   */
  public Result delete(String url) {
    Result result = new Result();
    result.setStatus(Status.BAD_REQUEST);

    Optional<Response> optional = Optional.empty();
    try {
      optional = Optional.ofNullable(this.get().target(url).request(Nsi.NSI_DDS_V1_XML).delete());
    } catch (Exception ex) {
      LOG.error("[DdsClient] DELETED failed for href={}, ex={}", url, ex);
    }
    finally {
      optional.ifPresent(r -> {
        result.setStatus(Status.fromStatusCode(r.getStatus()));
        r.close();
      });
    }

    return result;
  }
}
