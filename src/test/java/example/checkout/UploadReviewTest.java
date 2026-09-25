package example.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class UploadReviewTest {
    @Test void flaggedUploadIsHeldBeforeFulfillment() {
        assertEquals("QUARANTINED", UploadReview.decision(true));
        assertEquals("READY_FOR_FULFILLMENT", UploadReview.decision(false));
    }
}
