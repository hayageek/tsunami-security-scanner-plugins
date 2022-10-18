/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.tsunami.plugins.detectors.directorytraversal.genericpathtraversaldetector;

import com.google.common.collect.ImmutableSet;
import com.google.tsunami.common.net.FuzzingUtils;
import com.google.tsunami.common.net.http.HttpRequest;
import com.google.tsunami.proto.NetworkService;

/** An {@code InjectionPoint} that injects payloads as GET parameters. */
final class GetParameterInjection implements InjectionPoint {

  @Override
  public ImmutableSet<PotentialExploit> injectPayload(
      NetworkService networkService, HttpRequest request, String payload) {
    ImmutableSet.Builder<PotentialExploit> builder = ImmutableSet.builder();
    for (HttpRequest target : FuzzingUtils.fuzzGetParameters(request, payload)) {
      builder.add(
          PotentialExploit.create(networkService, target, payload, PotentialExploit.Priority.LOW));
    }
    return builder.build();
  }
}
