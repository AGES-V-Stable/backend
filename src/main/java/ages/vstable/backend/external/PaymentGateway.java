package ages.vstable.backend.external;



public interface PaymentGateway {
    Boolean getPaymentStatus(int paymentID);
}
