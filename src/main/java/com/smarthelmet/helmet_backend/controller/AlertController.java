package com.smarthelmet.helmet_backend.controller;

import com.smarthelmet.helmet_backend.dto.AlertRequest;
import com.smarthelmet.helmet_backend.model.Alert;
import com.smarthelmet.helmet_backend.model.Worker;
import com.smarthelmet.helmet_backend.repository.AlertRepository;
import com.smarthelmet.helmet_backend.repository.WorkerRepository;
import com.smarthelmet.helmet_backend.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import java.time.LocalDateTime;
import java.util.Optional;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 🔴 Create new alert
    // 🟢 Acknowledge alert
    @PostMapping
    public String sendAlert(@RequestBody AlertRequest alertRequest) {
        return workerRepository.findByHelmetId(alertRequest.getHelmetId())
                .map(worker -> {
                    Alert alert = new Alert();
                    alert.setHelmetId(alertRequest.getHelmetId());
                    alert.setMessage(alertRequest.getMessage());
                    alert.setAlertType("fall"); // or dynamic
                    alert.setLat(alertRequest.getLat());
                    alert.setLng(alertRequest.getLng());
                    alert.setTimestamp(LocalDateTime.now());
                    alertRepository.save(alert);

                    // 🔴 Send SMS to family + co-workers
                    // 🔴 Send SMS to family
                    notificationService.sendAlertSms(worker, alert);

                    // 🔴 Also notify all co-workers
                    List<Worker> allWorkers = workerRepository.findAll();
                    for (Worker w : allWorkers) {
                        if (!w.getHelmetId().equals(worker.getHelmetId())) {
                        notificationService.sendAlertToWorker(w, worker, alert);
                        }
                    }


                    // 🔴 WebSocket push
                    messagingTemplate.convertAndSend("/topic/alerts", alert);

                    return "✅ Alert sent.";
                })
                .orElse("❌ Worker with helmetId " + alertRequest.getHelmetId() + " not found.");
    }
    // 🟢 Get all alerts history
    @GetMapping
    public List<Alert> getAllAlerts() {
    return alertRepository.findAll();
}


    @PostMapping("/{helmetId}/ack")
public String acknowledgeAlert(@PathVariable String helmetId) {
    // Find the latest alert for this helmet
    List<Alert> alerts = alertRepository.findByHelmetId(helmetId);

    if (alerts.isEmpty()) {
        return "❌ Alert not found for helmetId " + helmetId;
    }

    // Get the most recent alert
    Alert alert = alerts.get(alerts.size() - 1);
    alert.setAcknowledged(true);
    alert.setAcknowledgedAt(LocalDateTime.now());
    alertRepository.save(alert);

    Optional<Worker> workerOpt = workerRepository.findByHelmetId(helmetId);

    if (workerOpt.isPresent()) {
        Worker worker = workerOpt.get();

        // Notify all other workers
        List<Worker> allWorkers = workerRepository.findAll();
        for (Worker w : allWorkers) {
            if (!w.getHelmetId().equals(worker.getHelmetId())) {
                notificationService.sendSafeSms(w, alert);
            }
        }

        // Notify family of the worker who acknowledged
        notificationService.sendFamilySms(worker, alert);

        // WebSocket push
        messagingTemplate.convertAndSend("/topic/alerts",
                "✅ Worker " + worker.getName() + " (Helmet: " + worker.getHelmetId() + ") is SAFE.");

        return "✅ Alert acknowledged, all workers and family notified.";
    }

    return "⚠️ Worker not found for this helmetId.";
}



}
