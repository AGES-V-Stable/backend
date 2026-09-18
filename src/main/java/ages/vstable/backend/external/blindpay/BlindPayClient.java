package ages.vstable.backend.external.blindpay;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BlindPayClient {

    private final BlindPayProperties properties;
}
