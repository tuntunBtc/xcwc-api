package com.yanji;

import com.fasterxml.jackson.databind.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bird/v1/me")
public class CloudPetController {
    private final CloudPetService pets;
    private final ObjectMapper json;
    private final ApiLinks links;
    public CloudPetController(CloudPetService pets,ObjectMapper json, ApiLinks links){this.links=links;this.pets=pets;this.json=json;}
    private <T> T parse(JsonNode body,Class<T> type) {
        try {
            if(body==null||!body.isObject())throw new IllegalArgumentException();
            for (JsonNode value:body) if(!value.isNull()&&!value.isTextual())throw new IllegalArgumentException();
            return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body);
        }catch(Exception e){throw new RegistrationService.Failure(HttpStatus.BAD_REQUEST,"INVALID_INPUT","Invalid command fields");}
    }
    @GetMapping("/pets")
    public Map<String,Object> list(@RequestHeader(value="Authorization",required=false) String auth){return pets.list(auth);}
    @GetMapping("/pets/{petId}")
    public Map<String,Object> detail(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String petId){return pets.detail(auth,petId);}
    @PostMapping("/pets/initialize")
    public Map<String,Object> initialize(@RequestHeader(value="Authorization",required=false) String auth){return pets.initializeAccount(auth);}
    @PostMapping("/pets/adopt")
    public ResponseEntity<?> adopt(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody JsonNode body){
        var result=pets.adopt(auth,parse(body,CloudPetService.Adoption.class));
        var pet=(Map<?,?>)result.get("pet");
        boolean created=Boolean.TRUE.equals(result.get("created"))&&!Boolean.TRUE.equals(result.get("replayed"));
        return ResponseEntity.status(created?201:200).location(URI.create(links.bird()+"/me/pets/"+pet.get("petId"))).body(result);
    }
    @PostMapping("/pets/{petId}/actions")
    public Map<String,Object> action(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String petId,@RequestBody JsonNode body){return pets.act(auth,petId,parse(body,CloudPetService.Command.class));}
    @GetMapping("/operations/{requestId}")
    public Map<String,Object> operation(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String requestId){return pets.operation(auth,requestId);}
    @GetMapping("/pets/{petId}/inventory-ledger")
    public List<Map<String,Object>> inventory(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String petId,@RequestParam(defaultValue="9223372036854775807") long beforeId){return pets.inventoryHistory(auth,petId,beforeId);}
    @GetMapping("/supply-claims")
    public List<Map<String,Object>> claims(@RequestHeader(value="Authorization",required=false) String auth,@RequestParam(defaultValue="9999-12-31") LocalDate beforeDate){return pets.claims(auth,beforeDate);}
}
