package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;

/**
 * The Performance Measurement Interceptor attaches timing information to
 * incoming requests in order to measure the time spent in different phases
 * of processing and the time spent in the hooks for each of the pointcuts it
 * records.
 *
 * In order to use it, register an instance of the PerformanceMeasurementInterceptor
 * before all other interceptors, and then another instance after all other interceptors.
 */

@Interceptor
public class PerformanceMeasurementInterceptor {
  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PerformanceMeasurementInterceptor.class);

  public static class MeasurementAttribute {
    public Long startNanoTime;
    public Long postProcessedBefore;
    public Long postProcessedAfter;
    public Long preHandledBefore;
    public Long preHandledAfter;
    public Long preResourceActionBefore;
    public Long preResourceActionAfter;
    public Long preSearchBefore;
    public Long preSearchAfter;
    public Long preShowBefore;
    public Long preShowAfter;
    public Long computationCompleteBefore;
    public Long computationCompleteAfter;

    public void recordStart() {
      if (startNanoTime == null) startNanoTime = System.nanoTime();
    }
    public void recordPostProcessed() {
      if (postProcessedBefore == null) postProcessedBefore = System.nanoTime();
      else postProcessedAfter = System.nanoTime();
    }
    public void recordPreHandled() {
      if (preHandledBefore == null) preHandledBefore = System.nanoTime();
      else preHandledAfter = System.nanoTime();
    }
    public void recordPreStorage() {
      if (preResourceActionBefore == null) preResourceActionBefore = System.nanoTime();
      else preResourceActionAfter = System.nanoTime();
    }
    public void recordPreSearch() {
      if (preSearchBefore == null)
        preSearchBefore = System.nanoTime();
      else
        preSearchAfter = System.nanoTime();
    }
    public void recordPreshow() {
      if (preShowBefore == null) preShowBefore = System.nanoTime();
      else preShowAfter = System.nanoTime();
    }
    public void recordComplete() {
      if (computationCompleteBefore == null) computationCompleteBefore = System.nanoTime();
      else computationCompleteAfter = System.nanoTime();
    }
    private String formatTimeNum(Long length) {
      if (length == null) return "n/a";
      return Long.toString(TimeUnit.MICROSECONDS.convert(length, TimeUnit.NANOSECONDS)) + "us";
    }
    private void formatSingleEntry(StringBuilder sb, Long beforeTime, Long afterTime, String descriptor) {
      sb.append("  ").append(descriptor)
        .append(": from start: ").append(formatTimeNum(beforeTime - startNanoTime))
        .append(" / spent inside: ").append(formatTimeNum(afterTime - beforeTime))
        .append('\n');
    }
    @Nullable
    public String formatTimingResult(RequestDetails requestDetails) {
      if (computationCompleteAfter == null) return null;
      StringBuilder sb = new StringBuilder(128);

      sb.append('\n');

      sb.append("timing of ").append(requestDetails.getRequestType().name()).append(" ").append(requestDetails.getRequestPath())
        .append(" (request id ").append(requestDetails.getRequestId()).append("):\n");

      formatSingleEntry(sb, postProcessedBefore, postProcessedAfter, "request post processed");
      formatSingleEntry(sb, preHandledBefore, preHandledAfter, "request pre handled   ");
      if (preResourceActionBefore != null)
        formatSingleEntry(sb, preResourceActionBefore, preResourceActionAfter, "pre resource action   ");
      if (preSearchBefore != null)
        formatSingleEntry(sb, preSearchBefore, preSearchAfter, "pre search registered ");
      formatSingleEntry(sb, preShowBefore, preShowAfter,       "pre resource show     ");
      formatSingleEntry(sb, computationCompleteBefore, computationCompleteAfter, "computation complete  ");
      sb.append("     total time: ");
      sb.append(formatTimeNum(computationCompleteAfter - startNanoTime));

      return sb.toString();
    }
  }

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
  public void postProcessHook(ServletRequestDetails requestDetails) {
    if (requestDetails == null || requestDetails.getOperation() != null && requestDetails.getOperation().equals("metadata")) {
      return;
    }
    Object attribute = requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
    MeasurementAttribute measurementObject;
    if (attribute == null) {
      measurementObject = new MeasurementAttribute();
      requestDetails.setAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute", measurementObject);
      measurementObject.recordStart();
    } else {
      measurementObject = (MeasurementAttribute) attribute;
    }
    measurementObject.recordPostProcessed();
  }

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
  public void preHandleHook(RequestDetails requestDetails) {
    try {
      final MeasurementAttribute measurementObject = (MeasurementAttribute) requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
      if (measurementObject == null) return;
      measurementObject.recordPreHandled();
    }
    catch (Exception e) {
      ourLog.warn("Exception trying to get measurement data attribute", e);
    }
  }

  @Hook(Pointcut.STORAGE_PRESEARCH_REGISTERED)
  public void preSearchHook(RequestDetails requestDetails) {
    try {
      final MeasurementAttribute measurementObject = (MeasurementAttribute) requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
      if (measurementObject == null) return;
      measurementObject.recordPreSearch();
    }
    catch (Exception e) {
      ourLog.warn("Exception trying to get measurement data attribute", e);
    }
  }

  @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
  public void createHook(RequestDetails requestDetails) {
    try {
      final MeasurementAttribute measurementObject = (MeasurementAttribute) requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
      if (measurementObject == null) return;
      measurementObject.recordPreStorage();
    }
    catch (Exception e) {
      ourLog.warn("Exception trying to get measurement data attribute", e);
    }
  }

  @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_DELETED)
  public void deleteHook(RequestDetails requestDetails) {
    try {
      final MeasurementAttribute measurementObject = (MeasurementAttribute) requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
      if (measurementObject == null) return;
      measurementObject.recordPreStorage();
    }
    catch (Exception e) {
      ourLog.warn("Exception trying to get measurement data attribute", e);
    }
  }

  @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
  public void updateHook(RequestDetails requestDetails) {
    try {
      final MeasurementAttribute measurementObject = (MeasurementAttribute) requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
      if (measurementObject == null) return;
      measurementObject.recordPreStorage();
    }
    catch (Exception e) {
      ourLog.warn("Exception trying to get measurement data attribute", e);
    }
  }

  @Hook(Pointcut.STORAGE_PRESHOW_RESOURCES)
  public void preshowHook(RequestDetails requestDetails) {
    try {
      final MeasurementAttribute measurementObject = (MeasurementAttribute) requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
      if (measurementObject == null) return;
      measurementObject.recordPreshow();
    }
    catch (Exception e) {
      ourLog.warn("Exception trying to get measurement data attribute", e);
    }
  }

  @Hook(Pointcut.SERVER_PROCESSING_COMPLETED)
  public void completeHook(RequestDetails requestDetails) {
    try {
      final MeasurementAttribute measurementObject = (MeasurementAttribute) requestDetails.getAttribute("ca.uhn.fhir.jpa.starter.PerformanceMeasurementInterceptor.MeasurementAttribute");
      if (measurementObject == null) return;
      measurementObject.recordComplete();

      final String formattedMessage = measurementObject.formatTimingResult(requestDetails);
      /*
       * There's always two measurement interceptors, and we only want the results printed on the second run.
       */
      if (formattedMessage != null) ourLog.info(formattedMessage);
    }
    catch (Exception e) {
      ourLog.warn("Exception trying to get measurement data attribute", e);
    }
  }
}
