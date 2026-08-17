package lu.zakaria.otelshow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class DiceController {

    private static final Logger log = LoggerFactory.getLogger(DiceController.class);

    private final Counter rolls;
    private final RestClient http;

    public DiceController(MeterRegistry registry) {
        this.rolls = Counter.builder("dice.rolls")
                .description("Number of dice rolls")
                .register(registry);
        // RestClient.create, not an injected builder: Boot 4 moved the builder
        // auto-config out of the webmvc starter, and the demo needs no customizers
        this.http = RestClient.create("http://localhost:8080");
    }

    @GetMapping("/roll")
    public Map<String, Object> roll(@RequestParam(defaultValue = "anonymous") String player)
            throws InterruptedException {
        int result = ThreadLocalRandom.current().nextInt(1, 7);
        if (result == 6) {
            Thread.sleep(1200); // an occasional slow request, so latency-based sampling has something to catch
        }
        rolls.increment();
        if (result == 1) {
            log.warn("player {} rolled a miserable {}", player, result);
        } else {
            log.info("player {} rolled {}", player, result);
        }
        return Map.of("player", player, "roll", result);
    }

    @GetMapping("/play")
    public Map<String, Object> play(@RequestParam(defaultValue = "zakaria") String player) {
        // outbound HTTP call to ourselves -> parent SERVER span + CLIENT span + nested SERVER span
        var result = http.get().uri("/roll?player={p}", player).retrieve().body(Map.class);
        log.info("game finished for {}", player);
        return Map.of("game", "dice", "result", result);
    }

    @GetMapping("/fail")
    public void fail() {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "the dice exploded");
    }
}
