package com.contentful.java.cda;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import retrofit.RestAdapter;
import retrofit.RestAdapter.LogLevel;
import retrofit.client.Client;
import retrofit.client.Response;
import rx.Observable;
import rx.functions.Func1;

import static com.contentful.java.cda.Constants.ENDPOINT_PROD;
import static com.contentful.java.cda.Constants.PATH_CONTENT_TYPES;
import static com.contentful.java.cda.Util.checkNotNull;

/**
 * Client to be used when requesting information from the Delivery API. Every client is associated
 * with exactly one Space, but there is no limit to the concurrent number of clients existing at
 * any one time. Avoid creating multiple clients for the same Space. Use {@link #builder()}
 * to create a new client instance.
 */
public final class CDAClient {
  final String spaceId;

  final String token;

  final CDAService service;

  final Cache cache;

  final Executor callbackExecutor;

  private CDAClient(Builder builder) {
    validate(builder);
    this.spaceId = builder.space;
    this.token = builder.token;
    this.service = createService(builder);
    this.cache = new Cache();
    this.callbackExecutor = Platform.get().callbackExecutor();
  }

  private void validate(Builder builder) {
    checkNotNull(builder.space, "Space ID must be provided.");
    checkNotNull(builder.token, "Access token must be provided.");
  }

  private CDAService createService(Builder clientBuilder) {
    String endpoint = clientBuilder.endpoint;
    if (endpoint == null) {
      endpoint = ENDPOINT_PROD;
    }

    RestAdapter.Builder restBuilder = new RestAdapter.Builder()
        .setEndpoint(endpoint)
        .setRequestInterceptor(new Interceptor(token));

    setLogLevel(restBuilder, clientBuilder);
    setClient(restBuilder, clientBuilder);
    return restBuilder.build().create(CDAService.class);
  }

  private void setClient(RestAdapter.Builder restBuilder, Builder clientBuilder) {
    if (clientBuilder.client != null) {
      restBuilder.setClient(clientBuilder.client);
    }
  }

  private void setLogLevel(RestAdapter.Builder restBuilder, Builder clientBuilder) {
    if (clientBuilder.logLevel != null) {
      restBuilder.setLogLevel(clientBuilder.logLevel);
    }
  }

  /**
   * Returns a {@link FetchQuery} for a given {@code type}, which can be used to fulfill the
   * request synchronously or asynchronously when a callback is provided.
   * @param type resource type.
   * @param <T> resource type.
   * @return query instance.
   */
  public <T extends CDAResource> FetchQuery<T> fetch(Class<T> type) {
    return new FetchQuery<T>(type, this);
  }

  /**
   * Returns an {@link ObserveQuery} for a given {@code type}, which can be used to return
   * an {@link Observable} that fetches the desired resources.
   * @param type resource type.
   * @param <T> resource type.
   * @return query instance.
   */
  public <T extends CDAResource> ObserveQuery<T> observe(Class<T> type) {
    return new ObserveQuery<T>(type, this);
  }

  /**
   * Returns a {@link SyncQuery} for initial synchronization via the Sync API.
   * @return query instance.
   */
  public SyncQuery sync() {
    return sync(null, null);
  }

  /**
   * Returns a {@link SyncQuery} for synchronization with the provided {@code syncToken} via
   * the Sync API.
   * @param syncToken sync token.
   * @return query instance.
   */
  public SyncQuery sync(String syncToken) {
    return sync(syncToken, null);
  }

  /**
   * Returns a {@link SyncQuery} for synchronization with an existing space.
   * @param synchronizedSpace space to sync.
   * @return query instance.
   */
  public SyncQuery sync(SynchronizedSpace synchronizedSpace) {
    return sync(null, synchronizedSpace);
  }

  private SyncQuery sync(String syncToken, SynchronizedSpace synchronizedSpace) {
    SyncQuery.Builder builder = SyncQuery.builder().setClient(this);
    if (synchronizedSpace != null) {
      builder.setSpace(synchronizedSpace);
    }
    if (syncToken != null) {
      builder.setSyncToken(syncToken);
    }
    return builder.build();
  }

  /** Fetches the space for this client (synchronously). */
  public CDASpace fetchSpace() {
    return observeSpace().toBlocking().first();
  }

  /** Fetches the space for this client (asynchronously). */
  @SuppressWarnings("unchecked")
  public <C extends CDACallback<CDASpace>> C fetchSpace(C callback) {
    return (C) Callbacks.subscribeAsync(observeSpace(), callback, this);
  }

  /** Returns an {@link Observable} that fetches the space for this client. */
  public Observable<CDASpace> observeSpace() {
    return cacheSpace(true);
  }

  /** Caching */
  Observable<Cache> cacheAll(final boolean invalidate) {
    return cacheSpace(invalidate)
        .flatMap(new Func1<CDASpace, Observable<Map<String, CDAContentType>>>() {
          @Override public Observable<Map<String, CDAContentType>> call(CDASpace cdaSpace) {
            return cacheTypes(invalidate);
          }
        })
        .map(new Func1<Map<String, CDAContentType>, Cache>() {
          @Override public Cache call(Map<String, CDAContentType> stringCDAContentTypeMap) {
            return cache;
          }
        });
  }

  Observable<CDASpace> cacheSpace(boolean invalidate) {
    CDASpace space = invalidate ? null : cache.space();
    if (space == null) {
      return service.space(spaceId).map(new Func1<Response, CDASpace>() {
        @Override public CDASpace call(Response response) {
          CDASpace tmp = ResourceFactory.space(response);
          cache.setSpace(tmp);
          return tmp;
        }
      });
    }
    return Observable.just(space);
  }

  Observable<Map<String, CDAContentType>> cacheTypes(boolean invalidate) {
    Map<String, CDAContentType> types = invalidate ? null : cache.types();
    if (types == null) {
      return service.array(spaceId, PATH_CONTENT_TYPES, null).map(
          new Func1<Response, Map<String, CDAContentType>>() {
            @Override public Map<String, CDAContentType> call(Response response) {
              CDAArray array = ResourceFactory.array(response, CDAClient.this);
              Map<String, CDAContentType> tmp = new ConcurrentHashMap<String, CDAContentType>();
              for (CDAResource resource : array.items()) {
                tmp.put(resource.id(), (CDAContentType) resource);
              }
              cache.setTypes(tmp);
              return tmp;
            }
          });
    }
    return Observable.just(types);
  }

  Observable<CDAContentType> cacheTypeWithId(String id) {
    CDAContentType contentType = cache.types().get(id);
    if (contentType == null) {
      return observe(CDAContentType.class).one(id).map(new Func1<CDAContentType, CDAContentType>() {
        @Override public CDAContentType call(CDAContentType resource) {
          if (resource != null) {
            cache.types().put(resource.id(), resource);
          }
          return resource;
        }
      });
    }
    return Observable.just(contentType);
  }

  /** Returns a {@link CDAClient} builder. */
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private Builder() {
    }

    String space;
    String token;
    String endpoint;
    LogLevel logLevel;
    Client client;

    /** Sets the space ID. */
    public Builder setSpace(String space) {
      this.space = space;
      return this;
    }

    /** Sets the space access token. */
    public Builder setToken(String token) {
      this.token = token;
      return this;
    }

    /** Sets a custom endpoint. */
    public Builder setEndpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /** Sets a custom log level. */
    public Builder setLogLevel(LogLevel logLevel) {
      this.logLevel = logLevel;
      return this;
    }

    /** Sets the endpoint to point the Preview API. */
    public Builder preview() {
      return this.setEndpoint(Constants.ENDPOINT_PREVIEW);
    }

    /** Sets a custom HTTP client. */
    public Builder setClient(Client client) {
      this.client = client;
      return this;
    }

    public CDAClient build() {
      return new CDAClient(this);
    }
  }
}
