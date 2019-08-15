package org.camunda.bpm.grpc.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.camunda.bpm.grpc.ExternalTaskGrpc;
import org.camunda.bpm.grpc.FetchAndLockReply;
import org.camunda.bpm.grpc.FetchAndLockRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.util.concurrent.Semaphore;

public class ExternalTaskClientGrpc implements Runnable {

  private String topic;
  private ManagedChannel channel;
  private ExternalTaskGrpc.ExternalTaskStub stub;
  private FetchAndLockRequest request;
  private Semaphore semaphore;

  private boolean isRunning;
  private Thread handlerThread;
  private StreamObserver<FetchAndLockRequest> requestObserver;

  public static final Logger log = LoggerFactory.getLogger(ExternalTaskClientGrpc.class);

  public ExternalTaskClientGrpc(String topic){

    this.topic = topic;
    this.channel = ManagedChannelBuilder.forAddress("localhost", 6565).usePlaintext().build();
    this.stub = ExternalTaskGrpc.newStub(channel);
    this.request = FetchAndLockRequest.newBuilder().setTopicName(topic).build();
    this.semaphore = new Semaphore(0);

  }

  public void start() {

    if (isRunning) {
      return;
    }

    log.info("Starting grpc client for topic " + topic);
    requestObserver = stub.fetchAndLock(new StreamObserver<FetchAndLockReply>() {

      @Override
      public void onNext(FetchAndLockReply var1) {
        log.info("Got a task, done it: " + var1.getId());
        semaphore.release();
      }

      @Override
      public void onError(Throwable var1) {
        log.info("Oh oh, error on sever", var1);
      }

      @Override
      public void onCompleted() {
        log.info("Server is done");
      }
    });

    isRunning = true;
    handlerThread = new Thread(this);
    handlerThread.start();
  }

  @Override
  public void run() {
    while(isRunning) {

      requestObserver.onNext(request);
      try {
        semaphore.acquire();
      } catch (InterruptedException e) {
        log.warn("Client was stopped while waiting on answer from server");
      }
    }

    requestObserver.onCompleted();
  }

  public void stop(){
    log.info("Stopping grpc client for topic " + topic);
    isRunning = false;
    try {
      handlerThread.interrupt();
      handlerThread = null;
    } catch (Exception e) {
      log.warn("Stop on client produced an exception", e);
    }
  }

  @PreDestroy
  public void destroy(){
    log.info("Shutting down channel ...");
    channel.shutdown();
  }

}