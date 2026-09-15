package ages.vstable.backend.support;

import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;

public final class OnboardingRequestFixtures {

    private OnboardingRequestFixtures() {
    }

    public static OnboardingRequestDTO requestValido() {
        OnboardingRequestDTO request = new OnboardingRequestDTO();
        request.setNomeCompleto("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setSenha("Senha@123");
        request.setConfirmarSenha("Senha@123");
        request.setRazaoSocial("Empresa Exemplo Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setPais("Brasil");
        request.setCep("90000-000");
        request.setCidade("Porto Alegre");
        request.setEstado("RS");
        return request;
    }
}
