package ages.vstable.backend.external.avenia;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AveniaClient {

    private final AveniaProperties properties;
}
