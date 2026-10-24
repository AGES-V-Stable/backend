package ages.vstable.backend.dto;

public record DeploymentSeedRequest(Admin admin, String stagingDemoPassword) {
    public record Admin(String fullName, String email, String password) {}
}
