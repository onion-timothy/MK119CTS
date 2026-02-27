package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.Error;
import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.ErrorService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
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

    @Autowired
    public WebController(DeviceService deviceService, SimulationService simulationService, ErrorService errorService) {
        this.deviceService = deviceService;
        this.simulationService = simulationService;
        this.errorService = errorService;
    }

    @RequestMapping("/")
    public String devices(@RequestParam(value = "group", required = false) Integer group, Model model) {
        if(group != null){
            model.addAttribute("group", group);
        } else {
            model.addAttribute("group", 1);
        }
        return "devices";
    }

    @RequestMapping("/devices")
    public String _devices(@RequestParam(value = "group", required = false) Integer group, Model model) {
        if(group != null){
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

    @RequestMapping("/editDevice/{id}")
    public String editDevice(Model model, @PathVariable Integer id) {
        model.addAttribute("device", deviceService.selectDevice(id));
        return "device_editor2";
    }

    @RequestMapping("/simulations")
    public String valueSimulation(@RequestParam(value = "device", required = false) Integer device, Model model) {
        if(device != null){
            model.addAttribute("device", device);
        } else {
            model.addAttribute("device", 0);
        }
        return "simulations";
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
        if(device != null){
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

    @RequestMapping("/livesimulation/{device_id}")
    public String livesimulation(Model model, @PathVariable Integer device_id) {
        model.addAttribute("device", deviceService.selectDevice(device_id));
        return "livesimulation";
    }
}
