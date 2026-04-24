package com.jinsu.villa.admin.controller;

import com.jinsu.villa.admin.dto.request.UserApprovalRequest;
import com.jinsu.villa.admin.dto.response.PendingUserResponse;
import com.jinsu.villa.admin.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    @PatchMapping("/users/{userId}/status")
    public void changeUserStatus(@PathVariable Long userId,
                                 @Valid @RequestBody UserApprovalRequest request) {
        adminService.changeUserStatus(userId, request);
    }

    @GetMapping("/users/pending")
    public List<PendingUserResponse> getPendingUsers() {
        return adminService.getPendingUsers();
    }
}