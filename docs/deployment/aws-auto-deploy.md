# GreyTest — Tự động cập nhật EC2 khi push main

Ngày chuẩn bị: 30/10/2026. Region: **Sydney `ap-southeast-2`**. Dùng hướng dẫn này thay bước upload bundle thủ công; không cài cả hai quy trình lên cùng installation.

**Trạng thái:** cấu hình được chuẩn bị trong PR; chưa xác minh publish/deploy AWS thật. Account hiện tại báo Feature unavailable khi mở IAM Identity providers. Vì vậy hướng dẫn dùng IAM user truy cập lập trình với quyền giới hạn, không cần OIDC hoặc Activate advanced features. Chỉ bật auto deploy sau khi cài lần đầu và kiểm tra website. Code local chưa commit/push không được build.

## 1. Luồng cập nhật

| Thao tác | Kết quả |
| --- | --- |
| Commit local, chưa push | Không deploy |
| Push/PR dev hoặc PR main | Test/build, không deploy |
| Main chưa bật AWS_PUBLISH_ENABLED | Test/build, chưa publish |
| Main đã cấu hình credentials, AWS_PUBLISH_ENABLED=true và AWS_DEPLOY_ENABLED=false/chưa đặt | Test/build → publish image ECR |
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

## 4. Tạo IAM user cho CI/CD, giữ Free plan

AWS báo Feature unavailable tại Identity providers thì không làm bước OIDC cũ. [Activate advanced features](https://docs.aws.amazon.com/accounts/latest/reference/activate-advanced-features.html) yêu cầu nâng gói và không thể hoàn tác. [AWS cho phép IAM user truy cập lập trình](https://docs.aws.amazon.com/accounts/latest/reference/sign-up-for-aws.html) trong trải nghiệm mới; không tạo user đăng nhập Console.

1. IAM → **Users → Create user**, User name: `greytest-github-deploy`. Không chọn **Provide user access to the AWS Management Console**.
2. Next → có thể để chưa gắn permission → Next → Create user. User chưa có quyền sử dụng cho tới khi hoàn tất policy dưới đây.
3. Mở user vừa tạo → **Permissions → Add permissions → Create inline policy → JSON**. Dùng [github-deploy-policy.json](iam/github-deploy-policy.json).
4. Thay mọi `REPLACE_ACCOUNT_ID` bằng account ID 12 số và `REPLACE_INSTANCE_ID` bằng EC2 đích; Next → policy name `GreyTestGithubDeployPolicy` → Create policy.
5. Trong user → **Security credentials → Access keys → Create access key**. Chọn trường hợp **Application running outside AWS**, xác nhận khuyến nghị và tạo key. Không dùng root access key.
6. Chỉ lưu Access key ID/Secret access key vào password manager và GitHub Environment Secrets ở bước 5. Secret chỉ hiện lúc tạo; không gửi vào chat, ảnh, code, .env trong Git hoặc Repository Variables. Không đưa key này vào EC2; EC2 vẫn dùng instance role ở bước 3.

User chỉ push/pull hai ECR repos và gửi lệnh tới đúng EC2, không được AdministratorAccess hoặc quyền tạo IAM users/keys. GetCommandInvocation dùng Resource `*` vì API không hỗ trợ scope instance. Quyền SendCommand cho phép thực thi lệnh trên EC2 đích; chỉ người tin cậy được sửa workflow/main hoặc Environment.

Khác OIDC, access key không tự hết hạn. Định kỳ thay key; khi thay cần cập nhật Secrets ở cả hai repo rồi kiểm chứng trước khi xóa key cũ. Khi kết thúc demo, vô hiệu hóa/xóa key và tắt publish/deploy. Nếu IAM user hoặc tạo key cũng bị chặn thì dừng, không nâng plan chỉ để đi tiếp. Khi account hỗ trợ OIDC, có thể chuyển lại dùng credentials tạm thời sau khi review.

## 5. Cấu hình GitHub Environment và bật publish, chưa bật deploy

Sau khi các PR đã review/merge, làm trong **cả hai repo**:

1. **Settings → Environments → New environment**, đặt đúng tên **`aws-production`**.
2. **Deployment branches and tags → Selected branches and tags → Add deployment branch or tag rule**: Ref type **Branch**, Name pattern **`main`**. Không chọn No restriction, không dùng wildcard hoặc Protected branches only khi chưa có branch protection. Không thêm rule cho dev/PR/tag.
3. Trong **Environment secrets → Add secret**, lưu hai giá trị từ IAM user:

| Secret name | Nội dung |
| --- | --- |
| AWS_ACCESS_KEY_ID | Access key ID |
| AWS_SECRET_ACCESS_KEY | Secret access key |

Keys chỉ có trong deployment job sau khi Environment cho phép branch main. Không đặt hai keys vào Repository Variables hoặc source. Repo hiện là public nên Environment secrets và branch restrictions có trên GitHub Free; nếu đổi repo thành private phải kiểm tra gói GitHub trước.

Không bật required reviewers/wait timer nếu muốn deploy tự động sau khi merge main. Vẫn bảo vệ quyền push/merge main. Chưa chạy workflow cho tới khi Environment, branch rule và secrets đã đủ.

Sau đó vào **Settings → Secrets and variables → Actions → Variables → New repository variable** (Repository Variables, không phải Environment variables):

| Name | Value |
| --- | --- |
| AWS_REGION | `ap-southeast-2` |
| AWS_ACCOUNT_ID | Account ID 12 số, giữ số 0 đầu nếu có |
| AWS_INSTANCE_ID | Instance ID `i-...` của GreyTest |
| AWS_PUBLISH_ENABLED | **`true`** sau khi Environment/secrets/policy đã đủ |
| AWS_DEPLOY_ENABLED | **`false`** |

Đây là Variables, không phải secrets; không cần AWS_ROLE_ARN. Vào Actions → GreyTest Backend CI → Run workflow → branch main. Chạy tương tự Frontend CI. Khi test/build đạt, bước Publish ghi image URI ở run Summary:

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

Giữ image đang chạy và một bản rollback trên EC2; chỉ xóa image cũ theo tag/digest đã đối chiếu inventory, không system prune/down -v. Theo dõi disk ECR/EC2 và budget. Dùng [CI backup/restore](aws-ci-recovery.md), không dùng phần backup/restore dành cho bundle thủ công của runbook. Lưu backup ngoài EC2 và xuất trước khi Free plan hết hạn/credit cạn. Việc IAM/access-key authentication, ECR push/pull, SSM deploy thật, TLS và recovery trên cloud chỉ được xác nhận sau khi thực thi; unit tests CLI giả không thay thế kiểm chứng AWS.

## Nguồn

- [AWS giới hạn trải nghiệm đăng ký mới](https://docs.aws.amazon.com/accounts/latest/reference/sign-up-for-aws.html)
- [Advanced features yêu cầu nâng account](https://docs.aws.amazon.com/accounts/latest/reference/activate-advanced-features.html)
- [AWS action: non-OIDC credentials](https://github.com/aws-actions/configure-aws-credentials#non-oidc-authentication-options)
- [GitHub Environment secrets và branch restrictions](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
- [AWS SendCommand](https://docs.aws.amazon.com/systems-manager/latest/userguide/example_ssm_SendCommand_section.html)
- [Compose interpolation](https://docs.docker.com/reference/compose-file/interpolation/)
- [ECR pricing](https://aws.amazon.com/ecr/pricing/)
- [Dịch vụ Free plan mới](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html)

ECR/IAM/STS/Systems Manager có trong danh sách dịch vụ được hỗ trợ của Free plan mới tại ngày chuẩn bị, nhưng không phải mọi tính năng IAM đều được mở (account này chặn Identity providers). Điều này không xác nhận mọi quyền/credit thực tế hoặc báo giá account của bạn.
