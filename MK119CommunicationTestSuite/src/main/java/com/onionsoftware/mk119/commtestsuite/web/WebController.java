package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.Simulation;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;


@Controller
public class WebController {
    private final DeviceService deviceService;
    private final SimulationService simulationService;

    @Autowired
    public WebController(DeviceService deviceService, SimulationService simulationService) {
        this.deviceService = deviceService;
        this.simulationService = simulationService;
    }

    @RequestMapping("/")
    public String devices() {
        return "devices";
    }

    @RequestMapping("/devices")
    public String _devices() {
        return "devices";
    }

    @RequestMapping("/addDevice")
    public String addDevice() {
        return "device_editor";
    }

    @RequestMapping("/editDevice/{id}")
    public String editDevice(Model model, @PathVariable Integer id) {
        model.addAttribute("device", deviceService.selectDevice(id));
        return "device_editor";
    }

    @RequestMapping("/simulations")
    public String editDevice() {
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
        model.addAttribute("device", deviceService.selectDevice(simulation.getDevice_id()));
        return "simulation_editor";
    }

    @RequestMapping("/settings")
    public String settings() {
        return "settings";
    }
}
