package com.smarthelmet.helmet_backend.service;

import com.smarthelmet.helmet_backend.model.Alert;
import com.smarthelmet.helmet_backend.model.Worker;
import com.smarthelmet.helmet_backend.config.TwilioConfig;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class NotificationService {

    private final TwilioConfig twilioConfig;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Value("${voice.call.delay-seconds:60}") // default 10 minutes if not set
    private int voiceCallDelaySeconds;
    
    @Value("${app.base-url}")
    private String appBaseUrl;


    // Store scheduled tasks so we can cancel them on ack
    private final Map<String, ScheduledFuture<?>> scheduledCalls = new ConcurrentHashMap<>();

    public NotificationService(TwilioConfig twilioConfig) {
        this.twilioConfig = twilioConfig;
        Twilio.init(twilioConfig.getAccountSid(), twilioConfig.getAuthToken());
    }

    // 🚨 Alert SMS
    public void sendAlertSms(Worker worker, Alert alert) {
        String h="https://www.google.com/maps?q=" + alert.getLat() + "," + alert.getLng();
        String sms = "🚨 ALERT!\nWorker: " + worker.getName() +
                "\nHelmet: " + worker.getHelmetId() +
                "\nMessage: " + alert.getMessage() +
                "\nLocation: " + h;

        sendSms(worker.getFamilyPhoneNumber(), sms);

        // Also schedule a voice call to family after delay
        scheduleVoiceCall(worker, alert);
    }

    // 🚨 Alert SMS to co-workers
    // 🚨 Alert SMS to co-workers
public void sendAlertToWorker(Worker receiver, Worker injuredWorker, Alert alert) {

    try {
        if (receiver == null || injuredWorker == null) return;

        String receiverPhone = receiver.getPhoneNumber();
        if (receiverPhone == null || receiverPhone.isEmpty()) return;

        // Add +91 if not present
        if (!receiverPhone.startsWith("+")) {
            receiverPhone = "+91" + receiverPhone;
        }

        String locationLink = "https://www.google.com/maps?q=" 
                + alert.getLat() + "," + alert.getLng();

        String sms = "🚨 ALERT!\n"
                + "Worker: " + injuredWorker.getName() + "\n"
                + "Helmet: " + injuredWorker.getHelmetId() + "\n"
                + "Message: " + alert.getMessage() + "\n"
                + "Location: " + locationLink;

        Message.creator(
                new PhoneNumber(receiverPhone),
                new PhoneNumber(twilioConfig.getTrialNumber()),
                sms
        ).create();

        System.out.println("📩 Alert SMS sent to co-worker: " + receiverPhone);

    } catch (Exception e) {
        System.out.println("❌ Failed to send co-worker alert SMS: " + e.getMessage());
    }
}


    // ✅ Safe SMS (to worker who was alerted)
    // ✅ SAFE SMS to co-workers
public void sendSafeSms(Worker receiver, Worker injuredWorker, Alert alert) {

    try {
        if (receiver == null || injuredWorker == null) return;

        String receiverPhone = receiver.getPhoneNumber();
        if (receiverPhone == null || receiverPhone.isEmpty()) return;

        if (!receiverPhone.startsWith("+")) {
            receiverPhone = "+91" + receiverPhone;
        }

        String message = "✅ SAFE\n"
                + "Worker: " + injuredWorker.getName() + "\n"
                + "Helmet: " + injuredWorker.getHelmetId() + "\n"
                + "Acknowledged at: " + alert.getAcknowledgedAt();

        Message.creator(
                new PhoneNumber(receiverPhone),
                new PhoneNumber(twilioConfig.getTrialNumber()),
                message
        ).create();

        System.out.println("📩 SAFE SMS sent to co-worker: " + receiverPhone);

    } catch (Exception e) {
        System.out.println("❌ Failed to send SAFE SMS: " + e.getMessage());
    }

    // Cancel pending voice call
    cancelScheduledVoiceCall(injuredWorker.getHelmetId());
}

    // ✅ Safe SMS to family
    public void sendFamilySms(Worker worker, Alert alert) {
        String familyNumber = worker.getFamilyPhoneNumber();
        if (familyNumber != null && !familyNumber.isEmpty()) {
            String message = "✅ Worker " + worker.getName() +
                    " (Helmet: " + worker.getHelmetId() + ") is SAFE now.";
            sendSms(familyNumber, message);
        }

        // Cancel pending voice call if acked
        cancelScheduledVoiceCall(worker.getHelmetId());
    }

    // Core method to send SMS
    private void sendSms(String toPhone, String body) {
        if (toPhone != null && !toPhone.startsWith("+")) {
            toPhone = "+91" + toPhone; // default India country code
        }
        if (toPhone == null || toPhone.isEmpty()) return;

        Message.creator(
                new PhoneNumber(toPhone),
                new PhoneNumber(twilioConfig.getTrialNumber()),
                body
        ).create();
    }

    // 🔔 Schedule a voice call to family after delay
    public void scheduleVoiceCall(Worker worker, Alert alert) {
        if (worker.getFamilyPhoneNumber() == null) return;

        Runnable callTask = () -> makeVoiceCall(worker, alert);

        ScheduledFuture<?> future = scheduler.schedule(
                callTask,
                voiceCallDelaySeconds,
                TimeUnit.SECONDS
        );

        // Save so we can cancel later if ack arrives
        scheduledCalls.put(worker.getHelmetId(), future);
        System.out.println("⏳ Voice call scheduled in " + voiceCallDelaySeconds + "s for helmetId: " + worker.getHelmetId());
    }

    // 🔔 Actually make Twilio voice call
    private void makeVoiceCall(Worker worker, Alert alert) {
        try {
            String familyNumber = worker.getFamilyPhoneNumber();
            if (familyNumber != null && !familyNumber.startsWith("+")) {
                familyNumber = "+91" + familyNumber;
            }

            if (familyNumber == null || familyNumber.isEmpty()) return;

            Call.creator(
                    new PhoneNumber(familyNumber),
                    new PhoneNumber(twilioConfig.getTrialNumber()),
                    new URI(appBaseUrl + "/voice/alert")
            ).create();

            System.out.println("📞 Voice call placed to " + familyNumber);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ❌ Cancel a scheduled voice call if alert is acknowledged
    public void cancelScheduledVoiceCall(String helmetId) {
        ScheduledFuture<?> future = scheduledCalls.remove(helmetId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            System.out.println("⏹️ Scheduled voice call cancelled for helmetId: " + helmetId);
        }
    }
}
