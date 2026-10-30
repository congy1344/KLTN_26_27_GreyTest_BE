# GreyTest — Các bước chuẩn bị AWS Console

> Cập nhật 30/10/2026: tài khoản triển khai dùng Sydney. Nếu muốn push main tự cập nhật EC2, sau bước 10 chuyển sang [hướng dẫn CI/CD](aws-auto-deploy.md), thay cho upload bundle thủ công ở bước 11. Không cài cả hai quy trình lên cùng installation.

Ngày 22/10/2026, dành cho tài khoản chưa tạo tài nguyên. Mục tiêu dự kiến: **một EC2 tại Sydney (`ap-southeast-2`), Docker Compose gồm web/Caddy + backend + PostgreSQL**, dùng credit của Free plan. Không tạo RDS, load balancer, NAT Gateway hoặc đăng ký domain trả tiền trong đợt này.

**Trạng thái:** hướng dẫn bám `docker-compose.aws.yml`, `.env.aws.example`, `Dockerfile.aws` và frontend Dockerfile/Caddyfile. Chưa đăng nhập account, xác minh credit, tạo tài nguyên hoặc kiểm chứng deploy trên EC2. Kết quả local được ghi riêng trong release evidence; không suy ra cloud đã chạy từ việc có cấu hình. Xem [runbook lệnh đầy đủ](aws-runbook.md) trước khi chạy phần terminal.

Phần chuẩn bị local đã có [báo cáo kiểm chứng ngày 22/10/2026](local-verification-20261022.md): build Linux, pipeline mock, recreate và restore tách biệt đạt. Cloud HTTPS và mức đủ tải của EC2 vẫn cần kiểm tra.

## 1. Xác minh account, plan, credit và region trước khi tạo máy

1. Đăng nhập [AWS Console](https://console.aws.amazon.com/).
2. Tại Console Home, tìm widget **Cost and Usage**. Ghi lại tên plan, credit còn lại và thời gian còn lại. Nếu có **AWS Settings**, mở phần Billing để xem thông tin gói. Account theo giao diện cũ có thể xem **Billing and Cost Management → Credits**.
3. Credit ban đầu là **100 USD**, có thể nhận thêm tối đa 100 USD khi hoàn thành hoạt động đủ điều kiện. Không ghi số dư 200 USD khi chưa nhìn thấy trên account. Free plan hết khi cạn credit hoặc đủ sáu tháng từ ngày mở account. [AWS Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/).
4. Giữ **Free plan** theo ưu tiên không trả tiền túi. Không chọn Upgrade Paid plan hoặc Activate advanced features để đi tiếp. Free plan hết hạn sẽ mất quyền truy cập tài nguyên; phải xuất backup trước thời hạn. [AWS account plans](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html).
5. Thử chọn **Asia Pacific (Sydney) / ap-southeast-2** trên thanh region.

**Region của tài khoản đang dùng là Sydney (`ap-southeast-2`):** tiếp tục trong region này, không cần chuyển sang Singapore hoặc nâng plan. Xác minh AWS Settings → Project → Overview → Additional Info → Region trước khi tạo tài nguyên. [AWS Regions for projects](https://docs.aws.amazon.com/accounts/latest/reference/project-regions.html).

IAM, Systems Manager, EC2, EBS và Budgets có trong danh sách dịch vụ Free Tier của trải nghiệm mới. Không cần bật AWS Organizations hoặc IAM Identity Center cho hướng dẫn này. [Dịch vụ được hỗ trợ](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html).

Điền thông tin để tiếp tục:

| Nội dung | Giá trị người dùng ghi lại |
| --- | --- |
| Plan thật | Free plan / tên hiển thị khác |
| Credit còn lại | ... USD |
| Ngày hết hạn | ... |
| Sydney chọn được | Có / Không |
| Email nhận cảnh báo | ... |

## 2. Bật MFA cho tài khoản quản trị

Nếu đăng nhập bằng root của account truyền thống:

1. Chọn tên account góc trên phải → **Security credentials**.
2. Tại **Multi-Factor Authentication (MFA)** chọn **Assign MFA device**.
3. Nhập tên thiết bị; chọn passkey/security key nếu có, hoặc Authenticator app.
4. Với ứng dụng xác thực: quét QR bằng điện thoại, nhập hai mã liên tiếp rồi hoàn tất.
5. Giữ phương thức khôi phục ở nơi riêng; không đưa QR, mã MFA hay key vào Git/chat.

[Hướng dẫn MFA root](https://docs.aws.amazon.com/IAM/latest/UserGuide/enable-virt-mfa-for-root.html), [lựa chọn MFA](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_mfa_enable_virtual.html).

Nếu account dùng trải nghiệm đăng nhập mới qua Google/GitHub/Builder ID, dùng cơ chế bảo vệ của đúng danh tính đó; không cố tạo root access key hoặc mở advanced features chỉ để giống ảnh hướng dẫn cũ. Tên menu có thể thay đổi theo đợt rollout.

## 3. Tạo budget theo dõi mức tiêu credit

1. Mở **Billing and Cost Management → Budgets → Create budget**.
2. Chọn **Customize (advanced) → Cost budget**.
3. Tên: `greytest-monthly-usage`; Period: **Monthly**; Renewal: **Recurring**; Method: **Fixed**.
4. Amount: **30 USD** làm ngưỡng cảnh báo ban đầu; đây không phải giá đã xác minh cho Sydney.
5. Để scope toàn account. Chọn **Unblended costs** trong Advanced options.
6. Nếu có lựa chọn **Credits/Refunds**, loại hai khoản này khỏi budget theo dõi usage, để credit bù trừ không che phần tiêu thụ. API tương ứng `IncludeCredit=false`, `IncludeRefund=false`; mặc định IncludeCredit là true. [AWS CostTypes](https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_budgets_CostTypes.html).
7. Thêm Actual 50%, Actual 80%, Forecasted 100%; điền email của người quản trị. Không thêm hành động tự động.
8. Review → **Create budget**. Xác minh email nhận cảnh báo khi AWS yêu cầu. [Tạo cost budget](https://docs.aws.amazon.com/cost-management/latest/userguide/create-cost-budget.html).

Budget là cảnh báo, không phải khóa chi phí tức thời. Số liệu có độ trễ. Theo dõi riêng số credit/ngày còn lại trong Console Home. [AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html).

Trước launch, kiểm tra estimate cho đúng Sydney: EC2 theo số giờ online + EBS 20 GiB + một public IPv4 + truyền dữ liệu/backup. Không dùng giá US East để khẳng định tổng phí. Không cần thêm dịch vụ có phí chỉ để xem estimate; có thể dùng [AWS Pricing Calculator công khai](https://calculator.aws/) nếu truy cập được mà không nâng gói.

## 4. Tạo role cho EC2 kết nối Session Manager

1. Tìm dịch vụ **IAM** → **Roles → Create role**.
2. Trusted entity type: **AWS service**.
3. Use case: **EC2** → Next.
4. Tìm policy **AmazonSSMManagedInstanceCore** và đánh dấu.
5. Next → Role name: `greytest-ec2-ssm` → Create role.
6. Giữ đúng role này để chọn khi launch. Không gắn AdministratorAccess vào role của máy chạy ứng dụng.

Đây là instance role cho agent trên EC2; quyền của người mở Console là phần riêng. [Tạo instance profile cho Systems Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-instance-permissions.html#instance-profile-add-permissions).

## 5. Kiểm tra mạng và tạo Security Group

1. Chọn region Sydney.
2. Mở **VPC → Your VPCs**, tìm **Default VPC**. Mở Subnets/Route tables để xác minh subnet có route `0.0.0.0/0 → igw-...`.
3. Default VPC thường có sẵn subnet công khai và internet gateway. Nếu không còn default VPC, dùng **Actions → Create default VPC** nếu được phép; không chọn wizard tạo NAT Gateway. [Default VPC](https://docs.aws.amazon.com/vpc/latest/userguide/default-vpc-components.html).
4. Mở **EC2 → Network & Security → Security Groups → Create security group**.
5. Name: `greytest-web-sg`; Description: `GreyTest demo web and temporary administration`; VPC: default VPC vừa kiểm tra.
6. Inbound rules:

| Type | Protocol / Port | Source | Thời gian giữ |
| --- | --- | --- | --- |
| HTTP | TCP / 80 | Anywhere IPv4 `0.0.0.0/0` | Giữ cho HTTP redirect và cấp chứng chỉ |
| HTTPS | TCP / 443 | Anywhere IPv4 `0.0.0.0/0` | Giữ cho website |
| SSH | TCP / 22 | **My IP**, địa chỉ quản trị `/32` | Chỉ lúc chuyển file qua SCP |

7. Giữ outbound mặc định trong đợt demo để tải package/image, truy cập SSM, GitHub và provider AI.
8. Create security group. Không thêm inbound 5432, 5433, 8080 hoặc 5173. [Rule web và SSH](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/security-group-rules-reference.html).

Nếu chưa chuyển file ngay, có thể tạo group chỉ 80/443 rồi thêm SSH đúng lúc cần. Không dùng SSH source Anywhere. Không cần UDP 443 cho HTTPS cơ bản.

## 6. Tạo key pair cho lần chuyển file qua SCP

SSM mở terminal trên trình duyệt, không thay cho thao tác upload file trực tiếp. Đợt đầu dùng SCP tạm thời để tránh thêm registry hoặc bucket.

1. Trong Sydney, mở **EC2 → Network & Security → Key Pairs → Create key pair**.
2. Name: `greytest-demo`.
3. Key pair type: **RSA**; Private key file format: **.pem** cho OpenSSH.
4. Create key pair; trình duyệt tải file. Lưu ngoài repo, ví dụ `C:\keys\greytest-demo.pem`. Đây là lần AWS cho tải private key.
5. Không gửi file `.pem` vào chat, không commit, không copy lên EC2. [EC2 key pairs](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/create-key-pairs.html).

Trên Windows, nếu OpenSSH báo quyền private key quá rộng: File Properties → Security → Advanced → Disable inheritance; chỉ giữ quyền đọc cho user hiện tại. Không dùng lệnh `chmod` dành cho Linux trong PowerShell Windows. [Quyền file key trên Windows](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/connection-prereqs-general.html).

## 7. Launch EC2

Mở **EC2 → Instances → Launch instances** và nhập:

| Trường | Lựa chọn |
| --- | --- |
| Name and tags | `greytest-demo`; tag Project=`GreyTest`, Environment=`demo` |
| Application and OS Images | Ubuntu Server **24.04 LTS**, 64-bit **x86 / amd64**, publisher Canonical |
| AMI offer | Official/free tier eligible; không chọn Ubuntu Pro/Marketplace offer có phần mềm thu phí |
| Instance type | **t3.small**, 2 vCPU / 2 GiB RAM |
| Key pair (login) | `greytest-demo` vừa tải `.pem` |
| Network settings → Edit → VPC/Subnet | Default VPC, một public subnet |
| Auto-assign public IP | **Enable** |
| Firewall | Select existing security group → `greytest-web-sg` |
| Configure storage | **20 GiB, gp3**, Encryption bật; giữ IOPS/throughput cơ bản |
| Advanced details → IAM instance profile | `greytest-ec2-ssm` |
| Advanced details → Credit specification | **Standard** |
| Metadata version | IMDSv2 / token Required nếu trường có sẵn |
| Number of instances | **1** |
| User data | Để trống; không đặt password/API key tại đây |

`t3.small` và gp3 thuộc nhóm được đánh dấu eligible cho account mới, nhưng vẫn tiêu credit. Chỉ launch khi Console xác nhận lựa chọn hợp lệ cho account này. [EC2 Free Tier](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html).

T3 mặc định Unlimited; chọn Standard để không tiêu surplus CPU credit. Khi cạn CPU credit, hiệu năng có thể bị giới hạn; cần đo sau deploy. [Thiết lập credit specification](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-performance-instances-how-to.html).

**Hai GiB RAM là cấu hình thử:** không chạy npm/Maven build trên máy này. Dùng một người demo từng job, đo RAM và CPU trước khi chốt đủ tải. Chưa có bằng chứng workload GreyTest đạt trên máy này.

Kiểm tra Summary rồi **Launch instance**. Nếu màn hình yêu cầu nâng plan, dừng. [Thông số launch EC2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/LaunchingAndUsingInstances.html).

Đợi trạng thái **Running** và các status checks pass; ghi Instance ID, Public IPv4, volume ID, security group ID.

## 8. Kiểm tra SSM trước khi đóng SSH

1. EC2 → Instances → chọn `greytest-demo` → **Connect**.
2. Chọn tab **Session Manager → Connect**.
3. Nếu mở được terminal, dùng SSM để quản trị lâu dài. Không cần mở inbound 22 cho SSM. [Bắt đầu session](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-sessions-start.html).
4. Nếu chưa connect được, kiểm tra role, agent và outbound HTTPS. SSM cần truy cập các endpoint `ssm`, `ssmmessages` và tương ứng `ec2messages` trong region. [Điều kiện SSM](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-prerequisites.html).
5. Ubuntu official thường cài sẵn SSM Agent; nếu thiếu, dùng đúng hướng dẫn Ubuntu, không cài chồng hai bản agent. [SSM Agent Ubuntu](https://docs.aws.amazon.com/systems-manager/latest/userguide/agent-install-ubuntu.html).

Chỉ xóa SSH sau khi SSM đã kiểm chứng kết nối. Nếu cần chẩn đoán bằng SSH thì inbound vẫn chỉ My IP.

## 9. Giữ một public IP ổn định và trỏ domain

1. EC2 → **Network & Security → Elastic IPs → Allocate Elastic IP address**.
2. Giữ pool Amazon, Network border group Sydney; Allocate đúng **một** IP.
3. Chọn IP → **Actions → Associate Elastic IP address**.
4. Resource type: Instance → chọn `greytest-demo` → Associate.
5. Dùng IP này trong DNS/SCP về sau. [Allocate/associate EIP](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/working-with-eips.html).

Elastic IP có chi phí kể cả lúc để trống hoặc dừng EC2; mục tiêu là giữ DNS ổn định, không phải ưu đãi IP miễn phí. Nếu muốn tiết kiệm khi offline dài ngày có thể dùng public IP tự động và cập nhật DNS sau stop/start, thay cho giữ EIP. Không cấp thêm EIP không dùng.

Nếu có domain: tạo **A record → Elastic IP** tại nhà quản lý DNS. Nếu chưa có domain, có thể tự đăng ký subdomain tại [DuckDNS](https://www.duckdns.org/) rồi cập nhật IPv4 của subdomain thành IP trên. Đây là thao tác ngoài AWS; không cần mua domain hoặc Route 53 hosted zone.

Không tạo AAAA record khi chưa cấu hình IPv6. Xác minh DNS trỏ đúng trước khi khởi động Caddy. Với public domain, Caddy cần DNS đúng, port 80/443 vào được và data directory ghi được/bền vững để cấp/giữ chứng chỉ. [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https).

## 10. Cài Docker trên Ubuntu qua terminal SSM

Chạy trong **Ubuntu terminal trên EC2**, không chạy các lệnh `apt` trong PowerShell local. Theo [Docker official — Install using the apt repository](https://docs.docker.com/engine/install/ubuntu/#install-using-the-repository): thêm GPG key và repository Docker, sau đó cài `docker-ce`, `docker-ce-cli`, `containerd.io`, `docker-buildx-plugin`, `docker-compose-plugin`.

Sau khi cài, kiểm tra:

```bash
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
sudo docker run --rm hello-world
df -h
free -h
```

Không dùng `docker-compose` v1 cũ. Dùng `sudo docker` trong đợt đầu để không phải thay quyền nhóm hệ thống. Không tạo VM mới chỉ vì chưa cài Docker.

## 11. Build ở local, chuyển image lên EC2

Chỉ tiếp tục sau khi test/build local và reviewer đạt. Tại local cần clone cả hai repo cạnh nhau: `D:\.KLTN\backend` và `D:\.KLTN\frontend`. Trên EC2 chỉ cần bundle đã build, không cần source hoặc thông tin đăng nhập GitHub.

PowerShell tại máy Windows, Docker Desktop đang chạy **Linux containers**:

```powershell
docker info --format '{{.OSType}}'
docker buildx build --platform linux/amd64 --load -f D:\.KLTN\backend\Dockerfile.aws -t greytest-backend:RELEASE_TAG D:\.KLTN\backend
docker buildx build --platform linux/amd64 --load -t greytest-frontend:RELEASE_TAG D:\.KLTN\frontend
docker pull --platform linux/amd64 postgres:15-alpine
docker save --output images.tar greytest-backend:RELEASE_TAG greytest-frontend:RELEASE_TAG postgres:15-alpine
Get-FileHash -Algorithm SHA256 -LiteralPath .\images.tar
```

Thay `RELEASE_TAG` bằng tag cố định của cặp commit đã kiểm tra. `--load` đưa image build vào local image store để `docker save` thấy được. [Docker build](https://docs.docker.com/reference/cli/docker/buildx/build/).

Dùng `--output`, không redirect binary qua `>` hoặc pipeline của PowerShell cũ. Kiểm tra exit code sau mỗi lệnh; [runbook](aws-runbook.md) có block build/export dừng khi lỗi. [Docker save](https://docs.docker.com/reference/cli/docker/image/save/).

Trước chuyển file, mở rule SSH 22 **My IP** nếu chưa có. Dùng native OpenSSH của Windows:

```powershell
scp -i 'C:\keys\greytest-demo.pem' '.\images.tar' ubuntu@PUBLIC_IP:/home/ubuntu/
```

Thay PUBLIC_IP bằng Elastic IP. Với Ubuntu AMI, username mặc định là `ubuntu`. Chuyển thư mục release gồm `images.tar`, `SHA256SUMS`, `docker-compose.aws.yml`, `.env.aws.example`, `release-manifest.json` và `smoke-aws.py` theo runbook; không cần clone private GitHub trên EC2. [SCP](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/linux-file-transfer-scp.html), [username theo AMI](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/connection-prereqs-general.html).

Kiểm tra fingerprint SSH theo thông tin instance; không tắt host key checking. File `.env` thật nếu chuyển bằng SCP phải chuyển riêng, quyền hạn hẹp; không đưa giá trị secret vào command arguments, screenshot hoặc log công khai.

Ubuntu terminal:

```bash
sha256sum /home/ubuntu/images.tar
sudo docker load --input /home/ubuntu/images.tar
sudo install -d -m 750 /opt/greytest/backend /opt/greytest/config
```

So checksum với local trước khi load. `docker load` giữ image/tag từ tar; không chạy build trên EC2. [Docker load](https://docs.docker.com/reference/cli/docker/image/load/).

Chép file Compose vào `/opt/greytest/backend/`, file môi trường thật vào `/opt/greytest/config/.env`; `sudo chmod 600 /opt/greytest/config/.env`. Nếu người dùng chưa có secrets, nhập riêng qua editor trong session; không để JWT/password mẫu.

File `.env` dùng đúng template: `RELEASE_TAG`, `SITE_ADDRESS`, `POSTGRES_PASSWORD`, `GREYTEST_AUTH_TOKEN_SECRET` và các biến provider AI. DB/user cố định là `greytest`; storage cố định `/app/storage`. JWT secret cần ngẫu nhiên ≥32 bytes. Dùng `LLM_PROVIDER=mock` cho smoke đầu tiên. API key AI không đặt trong frontend hoặc Docker build arguments.

## 12. Khởi động bản phát hành và kiểm tra

Lệnh chính; runbook có bước cài file và checksum trước đó:

```bash
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml config --quiet
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml up -d --no-build --pull never --wait --wait-timeout 300
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml ps
python3 /opt/greytest/backend/smoke-aws.py https://DOMAIN
```

`--env-file` cấp giá trị interpolation; service phải khai báo environment/env_file rõ ràng mới nhận biến vào container. Dùng `config --quiet` để không in secrets đã expand. [Compose interpolation](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/), [container environment](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/).

Kiểm tra lần lượt:

- PostgreSQL healthy, backend khởi động/Flyway thành công rồi web phục vụ được.
- `https://DOMAIN/api/ping` trả response API; `/api` còn nguyên qua proxy.
- Trang login và refresh một URL sâu của React hoạt động.
- Register/login, upload fixture demo mới, chạy pipeline bằng mock, download artifact.
- Restart/recreate giữ DB và toàn bộ source/coverage/revisions.
- Cấu hình demo chặn prefix `/api/payment*` tại edge bằng 503; giữ code VNPay, không coi payment đang vận hành. Nếu cần sandbox payment, làm một đợt cấu hình/kiểm tra riêng.
- Cấp ADMIN đầu tiên theo [runbook mục 5.1](aws-runbook.md#51-cấp-admin-đầu-tiên-sau-khi-user-tự-đăng-ký): user tự đăng ký, operator xác minh đúng ID/email rồi cấp role trong transaction qua psql nội bộ. Đăng nhập ADMIN qua `/admin/login`.
- Đo `sudo docker stats --no-stream`, disk và CPU credit; chưa kết luận 2 GiB đủ chỉ vì trang login mở được.

Compose phải chờ DB `service_healthy`; chỉ Running chưa chứng minh DB sẵn sàng. [Startup order](https://docs.docker.com/compose/how-tos/startup-order/). Health không chứng minh backup có thể khôi phục hoặc provider AI thật hoạt động.

## 13. Đóng SSH tạm và ghi inventory

1. Mở lại **Connect → Session Manager → Connect** để xác nhận SSM còn hoạt động.
2. EC2 → Security Groups → `greytest-web-sg` → **Inbound rules → Edit inbound rules**.
3. Xóa riêng rule SSH TCP 22 → **Save rules**. Giữ 80/443.
4. Những lần cần SCP backup/update sau: tạm thêm My IP, chuyển file, rồi xóa lại.
5. Ghi inventory ngoài secrets: region, instance/volume/SG/role/EIP IDs, domain, hai commit release, image tags, ngày launch và ngày credit/plan hết hạn.

## 14. Backup, dừng và kết thúc demo

- Tạo DB demo mới và upload lại fixture; chưa chuyển nguyên DB đường dẫn Windows sang Linux.
- Trước update/backup, chờ job kết thúc, ngừng mutation; lưu PostgreSQL dump + toàn bộ storage theo cùng thời điểm.
- Tải bản backup về máy quản trị, khôi phục thử vào môi trường tách biệt. Thủ tục cụ thể phải bám runbook bộ deploy actual.
- Khi dừng ngắn: EC2 → Instances → Instance state → Stop instance. Website offline; EBS và Elastic IP đang giữ vẫn có thể tiêu credit. [EC2 stop/start](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/how-ec2-instance-stop-start-works.html).
- Trước hết credit/plan, lấy backup ra khỏi AWS. Khi xóa môi trường phải kiểm tra EBS, snapshots và EIP còn sót; việc terminate/xóa dữ liệu cần quyết định đúng inventory.
- Không dùng `docker compose down -v` trong cập nhật thông thường.

AWS credit không chi trả API AI ngoài AWS. Chỉ chạy một kiểm tra provider thật nhỏ sau smoke mock và theo hạn mức provider riêng.

## Evidence — nguồn đã đọc và hành động

- Đã đọc plan `D:\.KLTN\plan\2026-10-22-aws-deployment-readiness-and-plan.md`, AGENTS.md và ponytail SKILL.md.
- Đã kiểm tra các nguồn primary được dẫn tại từng bước: AWS account plans/FAQ/regions/services, IAM/EC2/SSM/Budgets; Docker image/Compose/install; Caddy HTTPS.
- Đã phát hiện giới hạn region của signup experience mới; cần đối chiếu account thực trước khi dùng Sydney.
- Chỉ chuẩn bị tài liệu và cấu hình local. Không đăng nhập AWS, không launch tài nguyên trong bước chuẩn bị này.
- Kết quả build/smoke local phải ghi trong evidence của bản phát hành. Cloud HTTPS, full workload, backup/restore và khả năng đủ tải EC2 chỉ được đánh dấu đạt sau khi chạy thực tế.
