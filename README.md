# Brasfoot Transfer — Backend

Sistema de transferências automáticas de jogadores entre arquivos `.ban` do Brasfoot.

## Endpoints

| Método | Path | Descrição |
|--------|------|-----------|
| GET | `/api/transfer/health` | Health check |
| POST | `/api/transfer/preview` | Simula transferências (retorna JSON) |
| POST | `/api/transfer/process` | Aplica transferências (retorna ZIP) |

### Parâmetros (multipart/form-data)
- `transfers`: arquivo `.xlsx` ou `.csv` da liga
- `bans`: um ou mais arquivos `.ban` (repetir o campo para cada clube)

## Rodar localmente

```bash
mvn clean package -DskipTests
java -jar target/brasfoot-transfer-1.0.0.jar
```

## Deploy no Render

1. Suba este repositório no GitHub
2. No Render, crie um **New Web Service** → conecte o repositório
3. Selecione **Docker** como runtime
4. Adicione a variável de ambiente:
   - `ALLOWED_ORIGINS` = URL do seu app no Lovable (ex: `https://meu-app.lovable.app`)
5. Deploy!

O `render.yaml` já configura tudo automaticamente se você usar **Blueprint**.
