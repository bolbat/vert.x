/*
 * Copyright (c) 2011-2020 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.impl;

import io.netty.channel.EventLoop;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.context.storage.ContextLocal;
import io.vertx.core.spi.tracing.VertxTracer;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * A context that forwards most operations to a delegate. This context
 *
 * <ul>
 *  <li>maintains its own local data instead of the delegate.</li>
 * </ul>
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
final class DuplicatedContext extends ContextBase implements ContextInternal {

  final ContextImpl delegate;

  DuplicatedContext(ContextImpl delegate, Object[] locals) {
    super(locals);
    this.delegate = delegate;
  }

  @Override
  public ThreadingModel threadingModel() {
    return delegate.threadingModel();
  }

  @Override
  public boolean inThread() {
    return delegate.inThread();
  }

  @Override
  public final CloseFuture closeFuture() {
    return delegate.closeFuture();
  }

  @Override
  public final VertxTracer tracer() {
    return delegate.tracer();
  }

  @Override
  public final JsonObject config() {
    return delegate.config();
  }

  @Override
  public final Context exceptionHandler(Handler<Throwable> handler) {
    delegate.exceptionHandler(handler);
    return this;
  }

  @Override
  public Executor executor() {
    return delegate.executor();
  }

  @Override
  public final Handler<Throwable> exceptionHandler() {
    return delegate.exceptionHandler();
  }

  @Override
  public final EventLoop nettyEventLoop() {
    return delegate.nettyEventLoop();
  }

  @Override
  public final Deployment getDeployment() {
    return delegate.getDeployment();
  }

  @Override
  public final VertxInternal owner() {
    return delegate.owner();
  }

  @Override
  public final ClassLoader classLoader() {
    return delegate.classLoader();
  }

  @Override
  public WorkerPool workerPool() {
    return delegate.workerPool();
  }

  @Override
  public final void reportException(Throwable t) {
    delegate.reportException(t);
  }

  @Override
  public final ConcurrentMap<Object, Object> contextData() {
    return delegate.contextData();
  }

  @Override
  public <T> Future<T> executeBlockingInternal(Callable<T> action) {
    return ContextImpl.executeBlocking(this, action, delegate.internalWorkerPool, delegate.internalOrderedTasks);
  }

  @Override
  public <T> Future<T> executeBlockingInternal(Callable<T> action, boolean ordered) {
    return ContextImpl.executeBlocking(this, action, delegate.internalWorkerPool, ordered ? delegate.internalOrderedTasks : null);
  }

  @Override
  public final <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler, boolean ordered) {
    return ContextImpl.executeBlocking(this, blockingCodeHandler, delegate.workerPool, ordered ? delegate.orderedTasks : null);
  }

  @Override
  public final <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler, TaskQueue queue) {
    return ContextImpl.executeBlocking(this, blockingCodeHandler, delegate.workerPool, queue);
  }

  @Override
  public final <T> void execute(T argument, Handler<T> task) {
    delegate.execute(this, argument, task);
  }

  @Override
  public <T> void emit(T argument, Handler<T> task) {
    delegate.emit(this, argument, task);
  }

  @Override
  public void execute(Runnable task) {
    delegate.execute(this, task);
  }

  @Override
  public boolean isEventLoopContext() {
    return delegate.isEventLoopContext();
  }

  @Override
  public boolean isWorkerContext() {
    return delegate.isWorkerContext();
  }

  @Override
  public ContextInternal duplicate() {
    Object[] localsDuplicate = locals.length == 0 ? VertxImpl.EMPTY_CONTEXT_LOCALS : locals.clone();
    ContextLocal<?>[] contextLocals = ((VertxImpl) delegate.owner()).contextLocals;
    for (int i = 0;i < localsDuplicate.length;i++) {
      ContextLocalImpl<?> contextLocal = (ContextLocalImpl<?>) contextLocals[i];
      Object local = locals[i];
      if (local != null) {
        if (local != ContextLocalImpl.IDENTITY) {
          localsDuplicate[i] = duplicate(local, contextLocal);
        } else {
          localsDuplicate[i] = local;
        }
      }
    }
    return new DuplicatedContext(delegate, localsDuplicate);
  }

  private static <T> T duplicate(Object o, ContextLocalImpl<T> contextLocal) {
    if (contextLocal.type.isInstance(o)) {
      return contextLocal.duplicator.apply(contextLocal.type.cast(o));
    } else {
      throw new ClassCastException();
    }
  }

  @Override
  public ContextInternal unwrap() {
    return delegate;
  }

  @Override
  public boolean isDuplicate() {
    return true;
  }
}
