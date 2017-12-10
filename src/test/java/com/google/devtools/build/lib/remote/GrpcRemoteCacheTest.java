// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import com.google.api.client.json.GenericJson;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GrpcUtils;
import com.google.devtools.build.lib.remote.Digests.ActionKey;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystem.HashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc.ActionCacheImplBase;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsRequest;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsResponse;
import com.google.devtools.remoteexecution.v1test.GetActionResultRequest;
import com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Tests for {@link GrpcRemoteCache}. */
@RunWith(JUnit4.class)
public class GrpcRemoteCacheTest {
  private FileSystem fs;
  private Path execRoot;
  private FileOutErr outErr;
  private FakeActionInputFileCache fakeFileCache;
  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private final String fakeServerName = "fake server for " + getClass();
  private Server fakeServer;

  @Before
  public final void setUp() throws Exception {
    FileSystem.setDigestFunctionForTesting(HashFunction.SHA1);
    // Use a mutable service registry for later registering the service impl for each test case.
    fakeServer =
        InProcessServerBuilder.forName(fakeServerName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start();
    Chunker.setDefaultChunkSizeForTesting(1000); // Enough for everything to be one chunk.
    fs = new InMemoryFileSystem();
    execRoot = fs.getPath("/exec/root");
    FileSystemUtils.createDirectoryAndParents(execRoot);
    fakeFileCache = new FakeActionInputFileCache(execRoot);

    Path stdout = fs.getPath("/tmp/stdout");
    Path stderr = fs.getPath("/tmp/stderr");
    FileSystemUtils.createDirectoryAndParents(stdout.getParentDirectory());
    FileSystemUtils.createDirectoryAndParents(stderr.getParentDirectory());
    outErr = new FileOutErr(stdout, stderr);
  }

  @After
  public void tearDown() throws Exception {
    fakeServer.shutdownNow();
  }

  private static class CallCredentialsInterceptor implements ClientInterceptor {
    private final CallCredentials credentials;

    public CallCredentialsInterceptor(CallCredentials credentials) {
      this.credentials = credentials;
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
        MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions, Channel next) {
      assertThat(callOptions.getCredentials()).isEqualTo(credentials);
      // Remove the call credentials to allow testing with dummy ones.
      return next.newCall(method, callOptions.withCallCredentials(null));
    }
  }

  private GrpcRemoteCache newClient() throws IOException {
    AuthAndTLSOptions authTlsOptions = Options.getDefaults(AuthAndTLSOptions.class);
    authTlsOptions.authEnabled = true;
    authTlsOptions.authCredentials = "/exec/root/creds.json";
    authTlsOptions.authScope = "dummy.scope";

    GenericJson json = new GenericJson();
    json.put("type", "authorized_user");
    json.put("client_id", "some_client");
    json.put("client_secret", "foo");
    json.put("refresh_token", "bar");
    Scratch scratch = new Scratch();
    scratch.file(authTlsOptions.authCredentials, new JacksonFactory().toString(json));

    CallCredentials creds = GrpcUtils.newCallCredentials(
        scratch.resolve(authTlsOptions.authCredentials).getInputStream(),
        authTlsOptions.authScope);
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    Retrier retrier = new Retrier(remoteOptions);
    return new GrpcRemoteCache(
        ClientInterceptors.intercept(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(),
            ImmutableList.of(new CallCredentialsInterceptor(creds))),
        creds,
        remoteOptions,
        retrier);
  }

  @Test
  public void testDownloadEmptyBlob() throws Exception {
    GrpcRemoteCache client = newClient();
    Digest emptyDigest = Digests.computeDigest(new byte[0]);
    // Will not call the mock Bytestream interface at all.
    assertThat(client.downloadBlob(emptyDigest)).isEmpty();
  }

  @Test
  public void testDownloadBlobSingleChunk() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest digest = Digests.computeDigestUtf8("abcdefg");
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            assertThat(request.getResourceName().contains(digest.getHash())).isTrue();
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abcdefg")).build());
            responseObserver.onCompleted();
          }
        });
    assertThat(new String(client.downloadBlob(digest), UTF_8)).isEqualTo("abcdefg");
  }

  @Test
  public void testDownloadBlobMultipleChunks() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest digest = Digests.computeDigestUtf8("abcdefg");
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            assertThat(request.getResourceName().contains(digest.getHash())).isTrue();
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build());
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("def")).build());
            responseObserver.onNext(
                ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("g")).build());
            responseObserver.onCompleted();
          }
        });
    assertThat(new String(client.downloadBlob(digest), UTF_8)).isEqualTo("abcdefg");
  }

  @Test
  public void testDownloadAllResults() throws Exception {
    GrpcRemoteCache client = newClient();
    Digest fooDigest = Digests.computeDigestUtf8("foo-contents");
    Digest barDigest = Digests.computeDigestUtf8("bar-contents");
    Digest emptyDigest = Digests.computeDigest(new byte[0]);
    serviceRegistry.addService(
        new FakeImmutableCacheByteStreamImpl(fooDigest, "foo-contents", barDigest, "bar-contents"));

    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputFilesBuilder().setPath("a/foo").setDigest(fooDigest);
    result.addOutputFilesBuilder().setPath("b/empty").setDigest(emptyDigest);
    result.addOutputFilesBuilder().setPath("a/bar").setDigest(barDigest).setIsExecutable(true);
    client.download(result.build(), execRoot, null);
    assertThat(Digests.computeDigest(execRoot.getRelative("a/foo"))).isEqualTo(fooDigest);
    assertThat(Digests.computeDigest(execRoot.getRelative("b/empty"))).isEqualTo(emptyDigest);
    assertThat(Digests.computeDigest(execRoot.getRelative("a/bar"))).isEqualTo(barDigest);
    assertThat(execRoot.getRelative("a/bar").isExecutable()).isTrue();
  }

  @Test
  public void testUploadBlobCacheHitWithRetries() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest digest = Digests.computeDigestUtf8("abcdefg");
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          private int numErrors = 4;

          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            if (numErrors-- <= 0) {
              responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
              responseObserver.onCompleted();
            } else {
              responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
            }
          }
        });
    assertThat(client.uploadBlob("abcdefg".getBytes(UTF_8))).isEqualTo(digest);
  }

  @Test
  public void testUploadBlobSingleChunk() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest digest = Digests.computeDigestUtf8("abcdefg");
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(
                FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(digest).build());
            responseObserver.onCompleted();
          }
        });
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(
              final StreamObserver<WriteResponse> responseObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest request) {
                assertThat(request.getResourceName()).contains(digest.getHash());
                assertThat(request.getFinishWrite()).isTrue();
                assertThat(request.getData().toStringUtf8()).isEqualTo("abcdefg");
              }

              @Override
              public void onCompleted() {
                responseObserver.onNext(WriteResponse.newBuilder().setCommittedSize(7).build());
                responseObserver.onCompleted();
              }

              @Override
              public void onError(Throwable t) {
                fail("An error occurred: " + t);
              }
            };
          }
        });
    assertThat(client.uploadBlob("abcdefg".getBytes(UTF_8))).isEqualTo(digest);
  }

  static class TestChunkedRequestObserver implements StreamObserver<WriteRequest> {
    private final StreamObserver<WriteResponse> responseObserver;
    private final String contents;
    private Chunker chunker;

    public TestChunkedRequestObserver(
        StreamObserver<WriteResponse> responseObserver, String contents, int chunkSizeBytes) {
      this.responseObserver = responseObserver;
      this.contents = contents;
      try {
        chunker = new Chunker(contents.getBytes(UTF_8), chunkSizeBytes);
      } catch (IOException e) {
        fail("An error occurred:" + e);
      }
    }

    @Override
    public void onNext(WriteRequest request) {
      assertThat(chunker.hasNext()).isTrue();
      try {
        Chunker.Chunk chunk = chunker.next();
        Digest digest = chunk.getDigest();
        long offset = chunk.getOffset();
        ByteString data = chunk.getData();
        if (offset == 0) {
          assertThat(request.getResourceName()).contains(digest.getHash());
        } else {
          assertThat(request.getResourceName()).isEmpty();
        }
        assertThat(request.getFinishWrite())
            .isEqualTo(offset + data.size() == digest.getSizeBytes());
        assertThat(request.getData()).isEqualTo(data);
      } catch (IOException e) {
        fail("An error occurred:" + e);
      }
    }

    @Override
    public void onCompleted() {
      assertThat(chunker.hasNext()).isFalse();
      responseObserver.onNext(
          WriteResponse.newBuilder().setCommittedSize(contents.length()).build());
      responseObserver.onCompleted();
    }

    @Override
    public void onError(Throwable t) {
      fail("An error occurred: " + t);
    }
  }

  private Answer<StreamObserver<WriteRequest>> blobChunkedWriteAnswer(
      final String contents, final int chunkSize) {
    return new Answer<StreamObserver<WriteRequest>>() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<WriteRequest> answer(InvocationOnMock invocation) {
        return new TestChunkedRequestObserver(
            (StreamObserver<WriteResponse>) invocation.getArguments()[0], contents, chunkSize);
      }
    };
  }

  @Test
  public void testUploadBlobMultipleChunks() throws Exception {
    final Digest digest = Digests.computeDigestUtf8("abcdef");
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(
                FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(digest).build());
            responseObserver.onCompleted();
          }
        });

    ByteStreamImplBase mockByteStreamImpl = Mockito.mock(ByteStreamImplBase.class);
    serviceRegistry.addService(mockByteStreamImpl);
    for (int chunkSize = 1; chunkSize <= 6; ++chunkSize) {
      GrpcRemoteCache client = newClient();
      Chunker.setDefaultChunkSizeForTesting(chunkSize);
      when(mockByteStreamImpl.write(Mockito.<StreamObserver<WriteResponse>>anyObject()))
          .thenAnswer(blobChunkedWriteAnswer("abcdef", chunkSize));
      assertThat(client.uploadBlob("abcdef".getBytes(UTF_8))).isEqualTo(digest);
    }
    Mockito.verify(mockByteStreamImpl, Mockito.times(6))
        .write(Mockito.<StreamObserver<WriteResponse>>anyObject());
  }

  @Test
  public void testUploadCacheHits() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest fooDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("a/foo"), "xyz");
    final Digest barDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar"), "x");
    final Path fooFile = execRoot.getRelative("a/foo");
    final Path barFile = execRoot.getRelative("bar");
    barFile.setExecutable(true);
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            assertThat(request.getBlobDigestsList()).containsExactly(fooDigest, barDigest);
            // Nothing is missing.
            responseObserver.onNext(FindMissingBlobsResponse.getDefaultInstance());
            responseObserver.onCompleted();
          }
        });

    ActionResult.Builder result = ActionResult.newBuilder();
    client.upload(execRoot, ImmutableList.<Path>of(fooFile, barFile), outErr, result);
    ActionResult.Builder expectedResult = ActionResult.newBuilder();
    expectedResult.addOutputFilesBuilder().setPath("a/foo").setDigest(fooDigest);
    expectedResult
        .addOutputFilesBuilder()
        .setPath("bar")
        .setDigest(barDigest)
        .setIsExecutable(true);
    assertThat(result.build()).isEqualTo(expectedResult.build());
  }

  @Test
  public void testUploadCacheMissesWithRetries() throws Exception {
    final GrpcRemoteCache client = newClient();
    final Digest fooDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("a/foo"), "xyz");
    final Digest barDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar"), "x");
    final Digest bazDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("baz"), "z");
    final Path fooFile = execRoot.getRelative("a/foo");
    final Path barFile = execRoot.getRelative("bar");
    final Path bazFile = execRoot.getRelative("baz");
    ActionKey actionKey = Digests.unsafeActionKeyFromDigest(fooDigest); // Could be any key.
    barFile.setExecutable(true);
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          private int numErrors = 4;

          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            if (numErrors-- <= 0) {
              // Everything is missing.
              responseObserver.onNext(
                  FindMissingBlobsResponse.newBuilder()
                      .addMissingBlobDigests(fooDigest)
                      .addMissingBlobDigests(barDigest)
                      .addMissingBlobDigests(bazDigest)
                      .build());
              responseObserver.onCompleted();
            } else {
              responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
            }
          }
        });
    ActionResult.Builder rb = ActionResult.newBuilder();
    rb.addOutputFilesBuilder().setPath("a/foo").setDigest(fooDigest);
    rb.addOutputFilesBuilder().setPath("bar").setDigest(barDigest).setIsExecutable(true);
    rb.addOutputFilesBuilder().setPath("baz").setDigest(bazDigest);
    ActionResult result = rb.build();
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          private int numErrors = 4;

          @Override
          public void updateActionResult(
              UpdateActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            assertThat(request)
                .isEqualTo(
                    UpdateActionResultRequest.newBuilder()
                        .setActionDigest(fooDigest)
                        .setActionResult(result)
                        .build());
            if (numErrors-- <= 0) {
              responseObserver.onNext(result);
              responseObserver.onCompleted();
            } else {
              responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
            }
          }
        });
    ByteStreamImplBase mockByteStreamImpl = Mockito.mock(ByteStreamImplBase.class);
    serviceRegistry.addService(mockByteStreamImpl);
    when(mockByteStreamImpl.write(Mockito.<StreamObserver<WriteResponse>>anyObject()))
        .thenAnswer(
            new Answer<StreamObserver<WriteRequest>>() {
              private int numErrors = 4;

              @Override
              @SuppressWarnings("unchecked")
              public StreamObserver<WriteRequest> answer(InvocationOnMock invocation) {
                StreamObserver<WriteResponse> responseObserver =
                    (StreamObserver<WriteResponse>) invocation.getArguments()[0];
                return new StreamObserver<WriteRequest>() {
                  @Override
                  public void onNext(WriteRequest request) {
                    numErrors--;
                    if (numErrors >= 0) {
                      responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                      return;
                    }
                    assertThat(request.getFinishWrite()).isTrue();
                    String resourceName = request.getResourceName();
                    String dataStr = request.getData().toStringUtf8();
                    int size = 0;
                    if (resourceName.contains(fooDigest.getHash())) {
                      assertThat(dataStr).isEqualTo("xyz");
                      size = 3;
                    } else if (resourceName.contains(barDigest.getHash())) {
                      assertThat(dataStr).isEqualTo("x");
                      size = 1;
                    } else if (resourceName.contains(bazDigest.getHash())) {
                      assertThat(dataStr).isEqualTo("z");
                      size = 1;
                    } else {
                      fail("Unexpected resource name in upload: " + resourceName);
                    }
                    responseObserver.onNext(
                        WriteResponse.newBuilder().setCommittedSize(size).build());
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void onError(Throwable t) {
                    fail("An error occurred: " + t);
                  }
                };
              }
            });
    client.upload(actionKey, execRoot, ImmutableList.<Path>of(fooFile, barFile, bazFile), outErr);
    // 4 times for the errors, 3 times for the successful uploads.
    Mockito.verify(mockByteStreamImpl, Mockito.times(7))
        .write(Mockito.<StreamObserver<WriteResponse>>anyObject());
  }

  @Test
  public void testGetCachedActionResultWithRetries() throws Exception {
    final GrpcRemoteCache client = newClient();
    ActionKey actionKey = Digests.unsafeActionKeyFromDigest(Digests.computeDigestUtf8("key"));
    serviceRegistry.addService(
        new ActionCacheImplBase() {
          private int numErrors = 4;

          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onError(
                (numErrors-- <= 0 ? Status.NOT_FOUND : Status.UNAVAILABLE).asRuntimeException());
          }
        });
    assertThat(client.getCachedActionResult(actionKey)).isNull();
  }
}
