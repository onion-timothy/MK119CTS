package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationTargetValidator;
import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class WebController {
    private final DeviceService deviceService;
    private final SimulationService simulationService;
    private final ErrorService errorService;
    private final SimulationTargetValidator simulationTargetValidator;

    @Autowired
    public WebController(DeviceService deviceService, SimulationService simulationService,
                         ErrorService errorService, SimulationTargetValidator simulationTargetValidator) {
        this.deviceService = deviceService;
        this.simulationService = simulationService;
        this.errorService = errorService;
        this.simulationTargetValidator = simulationTargetValidator;
    }

    @RequestMapping("/")
    public String devices(@RequestParam(value = "group", required = false) Integer group, Model model) {
        if (group != null) {
            model.addAttribute("group", group);
        } else {
            model.addAttribute("group", 1);
        }
        return "devices";
    }

    @RequestMapping("/devices")
    public String _devices(@RequestParam(value = "group", required = false) Integer group, Model model) {
        if (group != null) {
            model.addAttribute("group", group);
        } else {
            model.addAttribute("group", 1);
        }
        return "devices";
    }

    @RequestMapping("/addDevice")
    public String addDevice() {
        return "device_editor2";
    }

    @RequestMapping("/devices/batch")
    public String batchAddDevices(@RequestParam(value = "group", required = false) Integer group, Model model) {
        model.addAttribute("group", group == null ? 1 : group);
        return "batchAddDevices";
    }

    @RequestMapping("/editDevice/{id}")
    public String editDevice(Model model, @PathVariable Integer id) {
        model.addAttribute("device", deviceService.selectDevice(id));
        return "device_editor2";
    }

    @RequestMapping("/simulations")
    public String valueSimulation(@RequestParam(value = "device", required = false) Integer device, 
                                  @RequestParam(value = "simulation", required = false) Integer simulationId, 
                                  Model model) {
        if (simulationId != null) {
            Simulation simulation = simulationService.selectSimulation(simulationId);
            if (simulation != null && simulation.getDevice() != null) {
                model.addAttribute("device", simulation.getDevice().getId());
                model.addAttribute("targetSimulation", simulationId);
            } else {
                model.addAttribute("device", device != null ? device : 0);
            }
        } else if (device != null) {
            model.addAttribute("device", device);
        } else {
            model.addAttribute("device", 0);
        }
        return "simulations";
    }

    @RequestMapping("/simulations/batch")
    public String batchAddSimulations(@RequestParam("device") Integer deviceId, Model model) {
        model.addAttribute("device", deviceService.selectDevice(deviceId));
        return "batchAddSimulations";
    }

    @RequestMapping("/addSimulation/{device_id}")
    public String addSimulation(Model model, @PathVariable Integer device_id) {
        model.addAttribute("device", deviceService.selectDevice(device_id));
        return "simulation_editor";
    }

    @RequestMapping("/editSimulation/{id}")
    public String editSimulation(Model model, @PathVariable Integer id) {
        Simulation simulation = simulationService.selectSimulation(id);
        model.addAttribute("simulation", simulation);
        model.addAttribute("device", deviceService.selectDevice(simulation.getDevice().getId()));
        return "simulation_editor";
    }

    @RequestMapping("/errors")
    public String errors(@RequestParam(value = "device", required = false) Integer device, Model model) {
        if (device != null) {
            model.addAttribute("device", device);
        } else {
            model.addAttribute("device", 0);
        }
        return "errors";
    }

    @RequestMapping("/addError/{device_id}")
    public String addError(Model model, @PathVariable Integer device_id) {
        model.addAttribute("device", deviceService.selectDevice(device_id));
        model.addAttribute("currentSystemErrors", errorService.selectErrorsByDeviceId(device_id));
        return "error_editor";
    }

    @RequestMapping("/errors/batch")
    public String batchAddErrors(@RequestParam("device") Integer deviceId, Model model) {
        model.addAttribute("device", deviceService.selectDevice(deviceId));
        return "batchAddErrors";
    }

    @RequestMapping("/editError/{error_id}")
    public String editError(Model model, @PathVariable Integer error_id) {
        Error error = errorService.selectErrorById(error_id);
        model.addAttribute("simulation", error);
        model.addAttribute("device", error.getDevice());
        model.addAttribute("currentSystemErrors", errorService.selectErrorsByDeviceId(error.getDevice().getId()));
        return "error_editor";
    }

    @RequestMapping("/settings")
    public String settings() {
        return "settings";
    }

    @RequestMapping("/devices/{device_id}/serverdata")
    public String serverdata(Model model, @PathVariable Integer device_id) {
        model.addAttribute("device", deviceService.selectDevice(device_id));
        return "serverdata";
    }

    @RequestMapping(value = "/saveSimulation", method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public String saveSimulation(@RequestParam("simulationJson") String simulationJson,
                                 org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Simulation simulation = objectMapper.readValue(simulationJson, Simulation.class);

            // 에디터 페이지도 REST 경로와 같은 target 검증을 받아야 한다.
            // 이 경로만 빠져 있으면 UI 로는 잘못된 target 이 그대로 등록된다.
            if (simulation.getDevice() != null) {
                String targetError = simulationTargetValidator.validate(
                        deviceService.selectDevice(simulation.getDevice().getId()), simulation);
                if (targetError != null) {
                    redirect.addFlashAttribute("saveError", targetError);
                    return "redirect:/simulations?device=" + simulation.getDevice().getId();
                }
            }

            if (simulation.getId() == null) {
                simulationService.insertSimulation(simulation);
            } else {
                simulationService.updateSimulation(simulation);
            }
            return "redirect:/simulations?simulation=" + simulation.getId();
        } catch (Exception e) {
            e.printStackTrace();
            return "redirect:/simulations";
        }
    }
}
