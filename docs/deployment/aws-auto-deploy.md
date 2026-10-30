# GreyTest — Tự động cập nhật EC2 khi push main

Ngày chuẩn bị: 30/10/2026. Region: **Sydney `ap-southeast-2`**. Dùng hướng dẫn này thay bước upload bundle thủ công; không cài cả hai quy trình lên cùng installation.

**Trạng thái:** cấu hình được chuẩn bị trong PR; chưa tạo ECR/OIDC/role hoặc xác minh deploy AWS thật. Chỉ bật auto deploy sau khi cài lần đầu và kiểm tra website. Code local chưa commit/push không được build.

## 1. Luồng cập nhật

| Thao tác | Kết quả |
| --- | --- |
| Commit local, chưa push | Không deploy |
| Push/PR dev hoặc PR main | Test/build, không deploy |
| Main chưa đặt AWS_ROLE_ARN | Test/build, chưa publish |
| Main có role, AWS_DEPLOY_ENABLED=false/chưa đặt | Test/build → publish image ECR |
| Main có AWS_DEPLOY_ENABLED=true | Test/build → publish → SSM cập nhật service EC2 |
| Backend đổi migration DB | Publish, nhưng dừng auto deploy để backup và xử lý thủ công |

Hai repo build độc lập, tag image là `sha-<40 ký tự commit>`, không dùng latest. Backend chỉ cập nhật backend; frontend chỉ cập nhật web. Khóa trên EC2 tránh ghi .env đồng thời. Run của commit main đã bị thay thế sẽ không deploy. Volumes và secrets được giữ; script không down -v hoặc prune. Restart có gián đoạn ngắn; hai repo không deploy thành một giao dịch chung, nên thay đổi API không tương thích cần phối hợp trong cửa sổ bảo trì.

Trước deploy backend, script so hash các file migration trong hai image; khác hash hoặc thiếu label thì dừng trước khi đổi app. Khi schema không đổi nhưng startup/HTTPS healthcheck thất bại, script thử khôi phục image/config cũ và CI báo lỗi. Đây không phải rollback dữ liệu/schema hoặc bảo đảm khôi phục mọi lỗi. Timeout các giai đoạn được giới hạn và dành ngân sách cho recovery.

Auto deploy chỉ thay image. Thay Compose, script deploy, PostgreSQL, env hoặc migration cần quản trị viên xử lý riêng. Giữ tối thiểu 2 GiB đĩa trống; thấp hơn thì script từ chối pull.

## 2. Tạo ECR repositories trên AWS — một lần

1. Amazon ECR, region Sydney → **Private registry → Repositories → Create repository**.
2. Tạo hai repo: `greytest/backend` và `greytest/frontend`.
3. Tag immutability: **Immutable**. Encryption: mặc định AES-256. Không cần customer KMS key hoặc enhanced scanning/Inspector trong lần setup này.
4. Trong từng repo → Lifecycle policy → Edit JSON, có thể giới hạn 10 image commit:

```json
{"rules":[{"rulePriority":1,"description":"Keep 10 recent commit images","selection":{"tagStatus":"tagged","tagPrefixList":["sha-"],"countType":"imageCountMoreThan","countNumber":10},"action":{"type":"expire"}}]}
```

Review preview trước khi lưu. Rule có thể xóa tag cũ từng deploy trên ECR; không dừng container đang chạy. Giữ image đang dùng và một bản rollback trên EC2; lưu bản cần giữ lâu riêng.

ECR private có phí lưu trữ và có thể có phí truyền dữ liệu, tính vào usage/credit. Free Tier không có nghĩa miễn phí vô hạn. Theo dõi budget hiện có; không cần CodePipeline, NAT Gateway, load balancer hoặc EC2 mới. Nếu yêu cầu nâng Paid plan thì dừng kiểm tra lại.

## 3. Cho EC2 quyền pull ECR

1. IAM → Roles → role đã gắn EC2, ví dụ `greytest-ec2-ssm`.
2. Giữ `AmazonSSMManagedInstanceCore`.
3. Add permissions → Create inline policy → JSON: dùng [ec2-ecr-pull-policy.json](iam/ec2-ecr-pull-policy.json).
4. Thay mọi `REPLACE_ACCOUNT_ID` bằng account ID 12 số, giữ số 0 đầu nếu có. Lưu tên `GreyTestEcrPull`.

Không cấp quyền push ECR hoặc AdministratorAccess cho EC2.

## 4. Tạo GitHub OIDC provider và deploy role

1. IAM → Identity providers → Add provider → **OpenID Connect**.
2. Provider URL: `https://token.actions.githubusercontent.com`; Audience: `sts.amazonaws.com`. Nếu provider đã có, dùng lại.
3. IAM → Policies → Create policy → JSON: dùng [github-deploy-policy.json](iam/github-deploy-policy.json).
4. Thay mọi `REPLACE_ACCOUNT_ID` và `REPLACE_INSTANCE_ID` bằng account/EC2 đích; lưu `GreyTestGithubDeployPolicy`.
5. IAM → Roles → Create role → **Custom trust policy**: dùng [github-trust.json](iam/github-trust.json), thay account ID.
6. Attach `GreyTestGithubDeployPolicy`, đặt tên role `GreyTestGithubDeploy`, tạo và copy Role ARN.

Trust chỉ chấp nhận main của hai repo GreyTest. Hai repo tạo ngày 20/06/2026 nên template dùng subject theo tên repo; nếu đã opt-in immutable/custom OIDC subject thì cần cập nhật đúng subject thực tế, không dùng wildcard để chữa AccessDenied. Workflow không gắn GitHub Environment vì environment thay đổi subject.

Role cho phép CI main thực thi lệnh trên đúng EC2 qua SSM; bảo vệ quyền push/merge main và sửa workflow. Không đưa AWS access key, file .pem, mật khẩu DB hay API key AI vào GitHub. Instance role chỉ pull; GitHub role chỉ push/pull hai ECR repos và gửi lệnh tới instance được chỉ định. GetCommandInvocation dùng Resource `*` vì API không hỗ trợ scope instance.

## 5. Bật publish trên GitHub, chưa bật deploy

Sau khi các PR CI/CD đã review/merge vào main, vào **cả hai repo → Settings → Secrets and variables → Actions → Variables → New repository variable**:

| Name | Value |
| --- | --- |
| AWS_REGION | `ap-southeast-2` |
| AWS_ROLE_ARN | ARN `arn:aws:iam::ACCOUNT_ID:role/GreyTestGithubDeploy` |
| AWS_INSTANCE_ID | Instance ID `i-...` của GreyTest |
| AWS_DEPLOY_ENABLED | **`false`** |

Đây là Variables, không phải secrets. Vào Actions → GreyTest Backend CI → Run workflow → branch main. Chạy tương tự Frontend CI. Khi test/build đạt, bước Publish ghi image URI ở run Summary:

```text
ACCOUNT_ID.dkr.ecr.ap-southeast-2.amazonaws.com/greytest/backend:sha-BACKEND_COMMIT
ACCOUNT_ID.dkr.ecr.ap-southeast-2.amazonaws.com/greytest/frontend:sha-FRONTEND_COMMIT
```

Lấy chính hai URI của run thành công. Hai SHA khác nhau; không dùng tag bundle 22/10. Nếu test/publish fail thì dừng. Rerun cùng commit có thể dùng lại immutable image đã publish thành công, không ghi đè tag.

## 6. Cài lần đầu trong terminal Session Manager

Điều kiện: Docker/Compose hoạt động, domain/DuckDNS trỏ đúng Elastic IP, SG mở 80/443. CI/CD này không cần SSH/SCP. Chạy **từng lệnh riêng, nhấn Enter và đợi dấu nhắc trở lại**.

Cài AWS CLI v2 x86_64 theo [hướng dẫn AWS](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html). Nếu `aws --version` đã có v2 thì không cài chồng:

```bash
sudo apt-get install -y unzip
```

```bash
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/greytest-awscli.zip
```

```bash
unzip -q -o /tmp/greytest-awscli.zip -d /tmp/greytest-awscli
```

```bash
sudo /tmp/greytest-awscli/aws/install
```

Không chạy aws configure hoặc nhập access key; CLI dùng instance role. Kiểm tra:

```bash
sudo aws sts get-caller-identity
```

Thay BACKEND_COMMIT trong URL bằng SHA đầy đủ của backend image ở bước 5:

```bash
curl -fsSL https://raw.githubusercontent.com/congy1344/KLTN_26_27_GreyTest_BE/BACKEND_COMMIT/scripts/deployment/bootstrap-ci.py -o /tmp/greytest-bootstrap-ci.py
```

Chạy **một dòng**, thay domain và cả hai image URI thật:

```bash
sudo python3 /tmp/greytest-bootstrap-ci.py --site YOUR_DOMAIN.duckdns.org --backend-image ACCOUNT_ID.dkr.ecr.ap-southeast-2.amazonaws.com/greytest/backend:sha-BACKEND_COMMIT --frontend-image ACCOUNT_ID.dkr.ecr.ap-southeast-2.amazonaws.com/greytest/frontend:sha-FRONTEND_COMMIT
```

Script tải Compose/smoke/deploy script từ đúng backend commit, tạo secrets ngẫu nhiên trong `/opt/greytest/config/.env` root-only, giữ provider mock, pull images và khởi động stack greytest-aws. deploy.conf lưu account/region. BACKEND_IMAGE/FRONTEND_IMAGE chọn phiên bản độc lập; RELEASE_TAG=ci-bootstrap vẫn có vì Compose kiểm tra cả nhánh fallback.

Bootstrap từ chối ghi đè .env. Nếu lỗi giữa chừng, không xóa .env/volumes để chạy lại; kiểm tra nguyên nhân theo mục 8 và tiếp tục với cấu hình cũ. Nếu chỉ smoke HTTPS fail, sửa DNS/certificate/SG rồi chạy lại smoke; không sinh secrets mới.

Mở website, đăng ký/login, upload fixture và thử pipeline mock. Cấp ADMIN theo [runbook mục 5.1](aws-runbook.md#51-cấp-admin-đầu-tiên-sau-khi-user-tự-đăng-ký). Key AI thật chỉ đặt trong .env trên EC2; AWS credit không thanh toán API AI bên ngoài AWS.

## 7. Bật tự động cập nhật

1. Chỉ sau khi website đạt: đổi AWS_DEPLOY_ENABLED thành **`true`** trong cả hai repo.
2. Xác minh SSM còn kết nối rồi xóa rule SSH 22 nếu không dùng nữa.
3. Sau này: làm trên dev → commit/push → review/merge main → Actions tự cập nhật service tương ứng.
4. Chỉ coi deploy thành công khi bước SSM kết thúc Success, không chỉ khi nhận CommandId.

Khi đổi migration, Compose, script deploy hoặc môi trường: tắt AWS_DEPLOY_ENABLED ở cả hai repo, **đợi các run/SSM command hiện tại kết thúc**, backup DB + storage theo [CI recovery](aws-ci-recovery.md), cập nhật thủ công và nghiệm thu trước khi bật lại. Đổi variable không hủy deployment đã bắt đầu. Root deploy script không tự tải bản mới sau mỗi commit; thay script cần cài lại bản đã review.

## 8. Theo dõi và xử lý lỗi

Trong SSM, từng lệnh riêng:

```bash
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml ps
```

```bash
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml logs --tail 100 backend web
```

```bash
sudo docker stats --no-stream
```

```bash
df -h
```

Nếu bootstrap đã tạo .env nhưng chưa startup xong, sửa nguyên nhân rồi dùng:

```bash
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml up -d --no-build --pull never --wait --wait-timeout 300
```

Nếu pull chưa xong vì IAM/login lỗi, đăng nhập ECR bằng instance role trong root shell rồi compose pull; không in registry password. Muốn rollback thủ công: tắt auto deploy hai repo, đợi command đang chạy hoàn tất, chọn URI cũ đã biết và dùng `sudo /opt/greytest/bin/deploy-image.sh backend IMAGE_URI` hoặc `web IMAGE_URI`; vẫn chịu schema/health gate. Không rollback schema bằng đổi image.

Giữ image đang chạy và một bản rollback trên EC2; chỉ xóa image cũ theo tag/digest đã đối chiếu inventory, không system prune/down -v. Theo dõi disk ECR/EC2 và budget. Dùng [CI backup/restore](aws-ci-recovery.md), không dùng phần backup/restore dành cho bundle thủ công của runbook. Lưu backup ngoài EC2 và xuất trước khi Free plan hết hạn/credit cạn. Việc IAM/OIDC, ECR push/pull, SSM deploy thật, TLS và recovery trên cloud chỉ được xác nhận sau khi thực thi; unit tests CLI giả không thay thế kiểm chứng AWS.

## Nguồn

- [GitHub OIDC với AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- [AWS SendCommand](https://docs.aws.amazon.com/systems-manager/latest/userguide/example_ssm_SendCommand_section.html)
- [Compose interpolation](https://docs.docker.com/reference/compose-file/interpolation/)
- [ECR pricing](https://aws.amazon.com/ecr/pricing/)
- [Dịch vụ Free plan mới](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html)

ECR/IAM/STS/Systems Manager có trong danh sách dịch vụ được hỗ trợ của Free plan mới tại ngày chuẩn bị. Điều này không xác nhận quyền/credit thực tế hoặc báo giá account của bạn.
