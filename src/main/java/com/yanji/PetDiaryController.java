package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bird/v1/me/pets/{petId}/diary")
public class PetDiaryController {
    private final PetDiaryService diaries;

    public PetDiaryController(PetDiaryService diaries) {
        this.diaries = diaries;
    }

    @GetMapping
    public ResponseEntity<PetDiaryService.Calendar> calendar(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String petId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return privateResponse(diaries.calendar(authorization, petId, from, to));
    }

    @PostMapping("/today")
    public ResponseEntity<PetDiaryService.Day> ensureToday(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String petId
    ) {
        return privateResponse(diaries.ensureToday(authorization, petId));
    }

    @GetMapping("/{date}")
    public ResponseEntity<PetDiaryService.Day> day(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String petId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return privateResponse(diaries.day(authorization, petId, date));
    }

    @PostMapping("/{date}/letters")
    public ResponseEntity<PetDiaryService.Day> leaveLetter(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String petId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestBody JsonNode body
    ) {
        if (!body.isObject() || body.size() != 2 || !body.path("requestId").isTextual() || !body.path("text").isTextual()) {
            throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请填写有效的留言和请求标识");
        }
        return privateResponse(diaries.leaveLetter(authorization, petId, date,
            new PetDiaryService.LetterInput(body.get("requestId").asText(), body.get("text").asText())));
    }

    @PostMapping("/{date}/letters/{letterId}/read")
    public ResponseEntity<PetDiaryService.Day> markRead(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String petId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @PathVariable String letterId
    ) {
        return privateResponse(diaries.markRead(authorization, petId, date, letterId));
    }

    private static <T> ResponseEntity<T> privateResponse(T result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
