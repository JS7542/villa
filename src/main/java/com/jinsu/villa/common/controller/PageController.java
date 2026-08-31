package com.jinsu.villa.common.controller;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class PageController {
  @GetMapping("/login")
  public String login(Model m) {
    m.addAttribute("mode", "login");
    return "auth";
  }

  @GetMapping("/signup")
  public String signup(Model m) {
    m.addAttribute("mode", "signup");
    return "auth";
  }

  @GetMapping("/reset")
  public String reset(Model m) {
    m.addAttribute("mode", "reset");
    return "auth";
  }

  @GetMapping("/")
  public String home(@AuthenticationPrincipal VillaPrincipal p, Model m) {
    m.addAttribute("user", p);
    m.addAttribute("admin", false);
    return "home";
  }

  @GetMapping("/manage")
  public String manage(@AuthenticationPrincipal VillaPrincipal p, Model m) {
    m.addAttribute("user", p);
    m.addAttribute("admin", true);
    return "home";
  }

  @GetMapping("/health")
  @ResponseBody
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
