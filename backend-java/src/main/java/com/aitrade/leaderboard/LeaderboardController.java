package com.aitrade.leaderboard;

import com.aitrade.leaderboard.dto.LeaderboardItem;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @GetMapping("/api/leaderboard")
    public List<LeaderboardItem> top(@RequestParam(defaultValue = "100") int limit) {
        return leaderboardService.top(limit);
    }
}
