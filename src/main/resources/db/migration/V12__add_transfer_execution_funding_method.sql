-- Instruções de pagamento conforme o método escolhido pelo pagador.
-- PIX continua em funding_instructions (código copia e cola).
-- TED usa funding_bank_transfer: código de referência e dados bancários devolvidos pela
-- BlindPay no payin, em JSON e apenas com os campos documentados.
-- Execuções antigas ficam com funding_method nulo; até aqui só existia o fluxo PIX.
ALTER TABLE public.transfer_executions
    ADD COLUMN funding_method varchar(20),
    ADD COLUMN funding_bank_transfer text;
