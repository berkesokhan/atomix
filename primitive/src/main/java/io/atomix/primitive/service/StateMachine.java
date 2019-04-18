/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.primitive.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * State machine.
 */
public interface StateMachine {

  /**
   * State machine context.
   */
  interface Context {

    /**
     * Returns the current state machine index.
     *
     * @return the current state machine index
     */
    long getIndex();

    /**
     * Returns the current state machine timestamp.
     *
     * @return the current state machine timestamp
     */
    long getTimestamp();

    /**
     * Returns the current role of the state machine.
     *
     * @return the current role of the state machine
     */
    Role getRole();

  }

  /**
   * Initializes the state machine context.
   *
   * @param context the state machine context
   */
  void init(Context context);

  /**
   * Backs up the state machine.
   *
   * @param output the output
   */
  void backup(OutputStream output);

  /**
   * Restores the state machine state.
   *
   * @param input the input
   */
  void restore(InputStream input);

  /**
   * Returns whether the given index can be deleted.
   *
   * @param index the index to check
   * @return indicates whether the given index can be deleted
   */
  boolean canDelete(long index);

  /**
   * Applies the given command to the state machine.
   *
   * @param command the command to apply
   * @return the state machine output
   */
  CompletableFuture<byte[]> apply(Command<byte[]> command);

  /**
   * Applies the given query to the state machine.
   *
   * @param query the query to apply
   * @return the state machine output
   */
  CompletableFuture<byte[]> apply(Query<byte[]> query);

}