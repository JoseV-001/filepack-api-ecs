# Teste de Carga K6

Script para testar a API FilePack e demonstrar o AutoScaling.

## Pré-requisitos

Docker instalado e em execução.

## Execução

```bash
# Definir URL do Load Balancer
export BASE_URL="http://filepack-alb-xxxxx.us-east-1.elb.amazonaws.com"

# Executar teste com a imagem oficial do k6
# No Git Bash do Windows, o MSYS_NO_PATHCONV evita que /work seja reescrito para a pasta do Git.
export PROJECT_ROOT=$(pwd -W)
MSYS_NO_PATHCONV=1 docker run --rm \
  -e BASE_URL="$BASE_URL" \
  -v "$PROJECT_ROOT:/work" \
  -w /work \
  grafana/k6 run k6/load-test.js
```

## Observar AutoScaling

Durante o teste, observe no CloudWatch ou Console:
- CPU das tasks subindo
- Novas tasks sendo provisionadas
- Distribuição entre AZs

Cada iteração envia 4 arquivos reais do diretório `data/`, com massa total acima de 5 MB por request. Esse conjunto foi calibrado para manter alta pressão de CPU sem bater no limite de upload nem derrubar a taxa de iterações do teste.
