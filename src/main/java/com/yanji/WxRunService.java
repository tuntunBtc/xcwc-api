package com.yanji;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 微信步数兑换叽分：服务端用 session_key 解密 → 校验 watermark → 按北京时间自然日归并步数 → 折算并发放（含同日幂等）。 */
@Service
public class WxRunService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int STEPS_PER_POINT_V2 = 1000;

    private final RegistrationService registration;
    private final PointsService points;
    private final WxSessionStore sessions;
    private final WxRunProperties props;
    private final Clock clock;

    public WxRunService(RegistrationService registration, PointsService points, WxSessionStore sessions,
                       WxRunProperties props, Clock clock) {
        this.registration = registration;
        this.points = points;
        this.sessions = sessions;
        this.props = props;
        this.clock = clock;
    }

    public record RedeemResult(int granted, int steps, String date, boolean claimed) {}
    public record StepProgress(String date, int steps, int stepPointsAvailable, int stepPointsRedeemed,
                               int granted, boolean replayed) {}
    record VerifiedDay(String user, LocalDate date, int steps) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RedeemResult redeem(String auth, String encryptedData, String iv, String requestId) {
        VerifiedDay day = verifyToday(auth, encryptedData, iv);
        int earned = StepSettlement.points(day.steps(), props.stepUnit, props.stepDailyCap);
        String date = day.date().toString();
        PointsService.Change change = points.awardSteps(day.user(), requestId, date, earned);
        return change == null ? new RedeemResult(0, day.steps(), date, false)
            : new RedeemResult(change.entry().delta(), day.steps(), date, true);
    }

    public StepProgress previewV2(String auth, String encryptedData, String iv) {
        VerifiedDay day = verifyToday(auth, encryptedData, iv);
        PointsService.StepProgress progress = points.previewStepProgress(day.user(), day.date(), day.steps() / STEPS_PER_POINT_V2);
        return new StepProgress(day.date().toString(), day.steps(), progress.available(), progress.redeemed(), 0, false);
    }

    public StepProgress redeemV2(String auth, String encryptedData, String iv, String requestId, String expectedDate) {
        VerifiedDay day = verifyToday(auth, encryptedData, iv);
        if (!day.date().toString().equals(expectedDate)) {
            throw new RegistrationService.Failure(HttpStatus.CONFLICT, "STEPS_DAY_CHANGED", "日期已更新，请重新读取微信步数");
        }
        PointsService.StepProgress progress = points.redeemStepProgress(day.user(), day.date(), requestId,
            day.steps() / STEPS_PER_POINT_V2);
        return new StepProgress(day.date().toString(), day.steps(), progress.available(), progress.redeemed(),
            progress.granted(), progress.replayed());
    }

    LocalDate localToday() {
        return clock.instant().atZone(SHANGHAI).toLocalDate();
    }

    VerifiedDay verifyToday(String auth, String encryptedData, String iv) {
        String user = registration.userId(auth);
        String sessionKey = sessions.sessionKey(auth.substring("Bearer ".length()));
        if (sessionKey == null) {
            throw new RegistrationService.Failure(HttpStatus.UNAUTHORIZED, "WX_SESSION_MISSING", "微信会话已失效，请重新进入小程序");
        }
        WxRunCrypto.StepInfo info;
        try {
            info = WxRunCrypto.parse(WxRunCrypto.decrypt(encryptedData, iv, sessionKey));
        } catch (Exception e) {
            throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST, "WERUN_DECRYPT_FAILED", "步数解密失败");
        }
        if (!WxRunCrypto.verifyWatermark(info, props.appId, clock, props.watermarkMaxAgeMillis())) {
            throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST, "WERUN_WATERMARK_INVALID", "步数数据校验未通过");
        }
        LocalDate date = localToday();
        return new VerifiedDay(user, date, WxRunCrypto.stepsForDate(info, date));
    }
}
