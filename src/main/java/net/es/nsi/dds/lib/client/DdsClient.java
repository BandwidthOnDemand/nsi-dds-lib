package net.es.nsi.dds.lib.client;

import com.google.common.base.Strings;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.bind.JAXBException;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.Optional;
import java.util.stream.Collectors;
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
import org.apache.http.client.utils.DateUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * TODO: Instantiate this client in the context of a DDS service.
 *
 * @author hacksaw
 */
public class DdsClient extends RestClient {
  private final Logger LOG = LogManager.getLogger(getClass());
  private final ObjectFactory FACTORY = new ObjectFactory();

  public DdsClient() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException,
          KeyManagementException, UnrecoverableKeyException, NoSuchProviderException {
    super();
  }

  public DdsClient(int maxConnPerRoute, int maxConnTotal, SecureType secure) throws KeyStoreException, IOException,
          NoSuchAlgorithmException, CertificateException, KeyManagementException, UnrecoverableKeyException,
          NoSuchProviderException {

    super(maxConnPerRoute, maxConnTotal, secure);
  }

  public DocumentsResult getDocuments(String baseURL) {
    WebTarget webTarget = this.get().target(baseURL).path("documents");

    return get(webTarget);
  }

  public DocumentsResult getDocumentsByType(String baseURL, String type) {
    WebTarget webTarget = this.get().target(baseURL).path("documents").queryParam("type", type);
    return get(webTarget);
  }

  public DocumentsResult getDocumentsByNsa(String baseURL, String nsa) {
    WebTarget webTarget = this.get().target(baseURL).path("documents").path(nsa);
    return get(webTarget);
  }

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
        LOG.error("DdsClient] Failed to retrieve list of documents = {}, result = {}",
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
   * @param baseURL
   * @return Object containing <b>Status.OK</b> if successful retrieval subscriptions has occurred.
   */
  public SubscriptionsResult getSubscriptions(String baseURL) {
    return getSubscriptions(baseURL, null);
  }

  /**
   * Get a list of DDS subscriptions for the specified NSA.
   *
   * @param baseURL
   * @param nsaId
   * @return Object containing <b>Status.OK</b> if successful retrieval subscriptions has occurred.
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
   * @param baseURL
   * @param url The direct reference URL for the remote subscription.
   * @return Object containing <b>Status.OK</b> if successful retrieval of target subscription has occurred.
   */
  public SubscriptionResult getSubscription(String baseURL, String url) {
    return getSubscription(baseURL, url, null);
  }

  /**
   * Get subscription associated with <b>url</b>.
   *
   * @param baseURL
   * @param url The direct reference URL for the remote subscription.
   * @param lastModified
   * @return Object containing <b>Status.OK</b> if successful retrieval of target subscription has occurred.
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
                DateUtils.formatDate(lastModified, DateUtils.PATTERN_RFC1123)).get());
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
   * Subscribe to DDS for asynchronous document delivery on all documents.
   *
   * @param baseURL
   * @param nsaId The requester NSA identifier for this client.
   * @param callbackURL
   * @return
   */
  public SubscriptionResult subscribe(String baseURL, String nsaId, String callbackURL) {
    // Allocate the object to hold our results.
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

    // Try to subscribe.
    WebTarget webTarget = this.get().target(baseURL).path("subscriptions");

    Optional<Response> optional = Optional.empty();
    try {
      optional = Optional.of(webTarget
              .request(Nsi.NSI_DDS_V1_XML)
              .header(HttpHeaders.CONTENT_ENCODING, "gzip")
              .post(Entity.entity(encoded, Nsi.NSI_DDS_V1_XML)));

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.CREATED.getStatusCode()) {
        // Looks like we were successful so save the subscription information.
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
   * Unsubscribe all remote DDS subscriptions associated with <b>nsaId</b> except the specified subscription <b>id</b>.
   *
   * @param baseURL
   * @param nsaId
   * @param id
   * @return
   */
  public SubscriptionsResult unsubscribe(String baseURL, String nsaId, String id) {
    SubscriptionsResult result = new SubscriptionsResult();
    result.setStatus(Status.BAD_REQUEST);

    Optional<Response> optional = Optional.empty();

    WebTarget webTarget = this.get().target(baseURL).path("subscriptions")
            .queryParam("requesterId", nsaId);

    try {
      optional = Optional.of(this.get().target(baseURL).path("subscriptions")
            .queryParam("requesterId", nsaId).request(Nsi.NSI_DDS_V1_XML).get());

      Response response = optional.get();
      if (response.getStatus() == Response.Status.OK.getStatusCode()) {
        // Looks like we were successful so save the subscription information.
        SubscriptionListType subscriptions = response.readEntity(SubscriptionListType.class);

        // For each subscription returned registered to our nsaId we check to
        // see if it is the one we just registered (current subscription).  If
        // it is not we delete the subscription.
        result.setSubscriptions(subscriptions.getSubscription()
                .stream()
                .filter(s -> (!id.equalsIgnoreCase(s.getId())))
                .map(s -> {
                  // Found one we need to remove.
                  LOG.debug("[DdsClient] found stale subscription {} on DDS {}", s.getHref(),
                          webTarget.getUri().toASCIIString());
                  unsubscribe(baseURL, s.getHref());
                  return s;
                }).collect(Collectors.toList()));
      } else {
        LOG.error("DdsClient] Failed to retrieve list of subscriptions {}, result = {}",
                webTarget.getUri().toASCIIString(), response.getStatusInfo().getReasonPhrase());
        ErrorType error = response.readEntity(ErrorType.class);
        if (error != null) {
          LOG.error("[DdsClient] operation returned, error={}.", error.getId());
        }
      }
    } catch (Exception ex) {
      LOG.error("[DdsClient] GET failed for href={}, ex={}", webTarget.getUri().toASCIIString(), ex);
    }
    finally {
      optional.ifPresent(r -> r.close());
    }

    return result;
  }

  /**
   * Unsubscribe a subscription identified by <b>url</b>.
   *
   * @param baseURL
   * @param url The absolute URL to the subscription to be deleted.
   * @return
   */

  public SubscriptionResult unsubscribe(String baseURL, String url) {
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
      optional = Optional.of(webTarget.request(Nsi.NSI_DDS_V1_XML).delete());

      Response response = optional.get();
      result.setStatus(Status.fromStatusCode(response.getStatus()));
      if (response.getStatus() == Status.NO_CONTENT.getStatusCode()) {
        // Looks like we successfully deleted the subscription.
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
   * Delete the resource identified by the specified <b>url</b>.
   *
   * @param url
   * @return
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
