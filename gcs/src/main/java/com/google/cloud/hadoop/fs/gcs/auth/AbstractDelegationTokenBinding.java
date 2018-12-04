/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.hadoop.fs.gcs.auth;

import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemBase;
import com.google.cloud.hadoop.util.AccessTokenProvider;
import com.google.common.base.Preconditions;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;


public abstract class AbstractDelegationTokenBinding {

  private static final Logger LOG =
      LoggerFactory.getLogger(AbstractDelegationTokenBinding.class);

  private final Text kind;

  protected SecretManager<AbstractGCPTokenIdentifier> secretManager =
      new TokenSecretManager();

  /**
   * URI of the filesystem.
   * Valid after {@link #bindToFileSystem(URI, GoogleHadoopFileSystemBase)}.
   */
  private URI canonicalUri;

  private Text service;

  /**
   * The owning filesystem.
   * Valid after {@link #bindToFileSystem(URI, GoogleHadoopFileSystemBase)}.
   */
  private GoogleHadoopFileSystemBase fileSystem;

  /**
   * Owner of the filesystem.
   * Valid after {@link #bindToFileSystem(URI, GoogleHadoopFileSystemBase)}.
   */
  private UserGroupInformation owner;


  protected AbstractDelegationTokenBinding(Text kind) {
    this.kind = kind;
  }

  public Text getKind() {
    return kind;
  }

  /**
   * Get the canonical URI of the filesystem, which is what is used to identify the tokens.
   * @return the URI.
   */
  public URI getCanonicalUri() {
    return canonicalUri;
  }

  /**
   * @return The bound file system
   */
  public GoogleHadoopFileSystemBase getFileSystem() {
    return fileSystem;
  }

  public Text getService() {
    return service;
  }

  /**
   * Return the name of the owner to be used in tokens.
   * This may be that of the UGI owner, or it could be related to
   * the GCS login.
   *
   * @return a text name of the owner.
   */
  public Text getOwnerText() {
    UserGroupInformation owner = getFileSystem().getOwner();
    return new Text(owner != null ? owner.getUserName() : "");
  }


  /**
   * Perform any actions when deploying unbonded, and return a list
   * of credential providers.
   * @throws IOException any failure.
   */
  public abstract AccessTokenProvider deployUnbonded() throws IOException;


  /**
   * Bind to the token identifier, returning the credential providers to use
   * for the owner to talk to S3, DDB and related GCP Services.
   * @param retrievedIdentifier the unmarshalled data
   * @return non-empty list of GCP credential providers to use for
   * authenticating this client with GCP services.
   * @throws IOException any failure.
   */
  public abstract AccessTokenProvider bindToTokenIdentifier(AbstractGCPTokenIdentifier retrievedIdentifier)
      throws IOException;

  /**
   * Bind to the filesystem.
   * Subclasses can use this to perform their own binding operations -
   * but they must always call their superclass implementation.
   * This <i>Must</i> be called before calling {@code init()}.
   *
   * <b>Important:</b>
   * This binding will happen during FileSystem.initialize(); the FS
   * is not live for actual use and will not yet have interacted with
   * GCS services.
   *
   * @param uri the canonical URI of the FS.
   * @param fs owning FS.
   *
   * @throws IOException failure.
   */
  public void bindToFileSystem(final URI uri,
                               final GoogleHadoopFileSystemBase fs)
      throws IOException {
    Preconditions.checkState((canonicalUri == null),
                             "bindToFileSystem called twice");
    this.canonicalUri = requireNonNull(uri);
    this.fileSystem = requireNonNull(fs);
    this.service = new Text(uri.toString());
    this.owner = fs.getOwner();
  }


  /**
   * Create a delegation token for the user.
   * This will only be called if a new DT is needed, that is: the
   * filesystem has been deployed unbound.
   *
   * @return the token
   *
   * @throws IOException if one cannot be created
   */
  public Token<AbstractGCPTokenIdentifier> createDelegationToken(String renewer)
      throws IOException {
    Text renewerText = new Text();
    if (renewer != null) {
      renewerText.set(renewer);
    }

    AbstractGCPTokenIdentifier tokenIdentifier =
        requireNonNull(createTokenIdentifier(renewerText),
                       "Token identifier");

    Token<AbstractGCPTokenIdentifier> token =
        new Token<>(tokenIdentifier, secretManager);
    token.setKind(getKind());
    token.setService(service);
    LOG.debug("Created token {} with token identifier {}",
        token, tokenIdentifier);
    return token;
  }

  /**
   * Create a token identifier with all the information needed
   * to be included in a delegation token.
   * This is where session credentials need to be extracted, etc.
   * This will only be called if a new DT is needed, that is: the
   * filesystem has been deployed unbound.
   *
   * If {@link #createDelegationToken}
   * is overridden, this method can be replaced with a stub.
   *
   * @return the token data to include in the token identifier.
   *
   * @throws IOException failure creating the token data.
   */
  public abstract AbstractGCPTokenIdentifier createTokenIdentifier(Text renewer)
      throws IOException;


  /**
   * Create a token identifier with all the information needed
   * to be included in a delegation token.
   * This is where session credentials need to be extracted, etc.
   * This will only be called if a new DT is needed, that is: the
   * filesystem has been deployed unbound.
   *
   * If {@link #createDelegationToken}
   * is overridden, this method can be replaced with a stub.
   *
   * @return the token data to include in the token identifier.
   *
   * @throws IOException failure creating the token data.
   */
  public abstract AbstractGCPTokenIdentifier createTokenIdentifier()
      throws IOException;


  /**
   * Create a new subclass of {@link AbstractGCPTokenIdentifier}.
   * This is used in the secret manager.
   * @return an empty identifier.
   */
  public abstract AbstractGCPTokenIdentifier createEmptyIdentifier();


  /**
   * Verify that a token identifier is of a specific class.
   * This will reject subclasses (i.e. it is stricter than
   * {@code instanceof}, then cast it to that type.
   * @param identifier identifier to validate
   * @param expectedClass class of the expected token identifier.
   * @throws DelegationTokenIOException If the wrong class was found.
   */
  protected <T extends AbstractGCPTokenIdentifier> T convertTokenIdentifier(
      final AbstractGCPTokenIdentifier identifier,
      final Class<T> expectedClass) throws DelegationTokenIOException {
    if (!identifier.getClass().equals(expectedClass)) {
      throw new DelegationTokenIOException(DelegationTokenIOException.TOKEN_WRONG_TYPE +
                                           "; expected a token identifier of type " +
                                           expectedClass +
                                           " but got " +
                                           identifier.getClass() +
                                           " and kind " + identifier.getKind());
    }
    return (T) identifier;
  }

  /**
   * The secret manager always uses the same secret; the
   * factory for new identifiers is that of the token manager.
   */
  protected class TokenSecretManager extends SecretManager<AbstractGCPTokenIdentifier> {

    private final byte[] pwd = "not-a-pass".getBytes(StandardCharsets.UTF_8);

    @Override
    protected byte[] createPassword(AbstractGCPTokenIdentifier identifier) {
      return pwd;
    }

    @Override
    public byte[] retrievePassword(AbstractGCPTokenIdentifier identifier) throws InvalidToken {
      return pwd;
    }

    @Override
    public AbstractGCPTokenIdentifier createIdentifier() {
      return AbstractDelegationTokenBinding.this.createEmptyIdentifier();
    }
  }

}
