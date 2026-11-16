package com.yanji;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Supplies are independent of the points wallet. The client can apply one verified receipt locally. */
@Service
public class SupplyService {
    private final JdbcTemplate jdbc;
    private final RegistrationService registration;
    private final WxRunService steps;
    private final WxRunProperties props;

    public SupplyService(JdbcTemplate jdbc, RegistrationService registration, WxRunService steps, WxRunProperties props) {
        this.jdbc = jdbc;
        this.registration = registration;
        this.steps = steps;
        this.props = props;
    }

    public record Receipt(String id, String date, String birdCode, int food, int water) {}

    public record Result(String date, int steps, int requiredSteps, boolean claimed, boolean claimable,
                         @JsonInclude(JsonInclude.Include.ALWAYS) Receipt receipt) {}

    private record StoredClaim(String id, String installationHash, String birdCode) {}

    public Result preview(String auth, String encryptedData, String iv, String installationId, String birdCode) {
        requireEnabled();
        BirdCodes.validate(birdCode);
        var day = steps.verifyToday(auth, encryptedData, iv);
        StoredClaim prior = findClaim(day);
        if (prior == null && day.steps() >= props.supplySteps) registration.findByCode(birdCode);
        return result(day, prior, RegistrationService.digest(installationId), birdCode);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result claim(String auth, String encryptedData, String iv, String installationId, String birdCode,
                        String expectedDate) {
        requireEnabled();
        BirdCodes.validate(birdCode);
        LocalDate expected;
        try {
            expected = LocalDate.parse(expectedDate);
        } catch (DateTimeParseException error) {
            throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "补给日期格式不正确");
        }
        var day = steps.verifyToday(auth, encryptedData, iv);
        if (!day.date().equals(expected)) throw dayChanged();

        // Account lock serializes first grants for the same WeChat user. The primary key is the database backstop.
        jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, day.user());
        if (jdbc.queryForObject("SELECT cloud_pet_enabled FROM app_user WHERE id=?",Boolean.class,day.user()))
            throw new RegistrationService.Failure(HttpStatus.CONFLICT,"SUPPLY_DISABLED","Use the cloud daily supply action");
        if (!day.date().equals(steps.localToday())) throw dayChanged();
        StoredClaim prior = findClaim(day);
        String installationHash = RegistrationService.digest(installationId);
        if (prior != null || day.steps() < props.supplySteps) return result(day, prior, installationHash, birdCode);

        registration.findByCode(birdCode); // Public entity must exist and still be active for a new grant.
        if (!day.date().equals(steps.localToday())) throw dayChanged();
        var receipt = new StoredClaim(registration.randomKey(16), installationHash, birdCode);
        jdbc.update("INSERT INTO wx_supply_claim(user_id,claim_date,receipt_id,installation_hash,bird_code) VALUES(?,?,?,?,?)",
            day.user(), day.date(), receipt.id(), receipt.installationHash(), receipt.birdCode());
        return result(day, receipt, installationHash, birdCode);
    }

    private void requireEnabled() {
        if (!props.supplyEnabled || props.supplySteps < 1) {
            throw new RegistrationService.Failure(HttpStatus.SERVICE_UNAVAILABLE, "SUPPLY_DISABLED", "微信步数补给尚未开放");
        }
    }

    private RegistrationService.Failure dayChanged() {
        return new RegistrationService.Failure(HttpStatus.CONFLICT, "SUPPLY_DAY_CHANGED", "日期已更新，请重新读取微信步数");
    }

    private StoredClaim findClaim(WxRunService.VerifiedDay day) {
        return jdbc.query("SELECT receipt_id,installation_hash,bird_code FROM wx_supply_claim WHERE user_id=? AND claim_date=?",
            (row, index) -> new StoredClaim(row.getString(1), row.getString(2), row.getString(3)), day.user(), day.date())
            .stream().findFirst().orElse(null);
    }

    private Result result(WxRunService.VerifiedDay day, StoredClaim claim, String installationHash, String birdCode) {
        String date = day.date().toString();
        Receipt receipt = claim != null && claim.installationHash().equals(installationHash) && claim.birdCode().equals(birdCode)
            ? new Receipt(claim.id(), date, claim.birdCode(), 1, 1) : null;
        return new Result(date, day.steps(), props.supplySteps, claim != null,
            claim == null && day.steps() >= props.supplySteps, receipt);
    }
}
