package com.conca.ontology.eval.api;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.eval.dto.EvalCustomGraderDTO;
import com.conca.ontology.eval.dto.EvalCustomGraderTestDTO;
import com.conca.ontology.eval.dto.EvalDatasetDTO;
import com.conca.ontology.eval.dto.EvalHumanScoreDTO;
import com.conca.ontology.eval.dto.EvalRunDTO;
import com.conca.ontology.eval.dto.EvalSampleDTO;
import com.conca.ontology.eval.dto.FromChatSampleResultDTO;
import com.conca.ontology.eval.service.EvalCustomGraderService;
import com.conca.ontology.eval.service.EvalDatasetService;
import com.conca.ontology.eval.service.EvalRunService;
import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.shared.web.ApiException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * @author tianbaoxing
 * @date 2026-08-26
 */
@RestController
@RequestMapping("/api/eval")
@Tag(name = "eval", description = "评测中心：数据集驱动 agent 问答并多评测器判分（规则评测器 + LLM 判分）")
public class EvalController {

    private final EvalDatasetService datasetService;
    private final EvalCustomGraderService customGraderService;
    private final EvalRunService runService;

    public EvalController(EvalDatasetService datasetService,
            EvalCustomGraderService customGraderService,
            EvalRunService runService) {
        this.datasetService = datasetService;
        this.customGraderService = customGraderService;
        this.runService = runService;
    }

    @GetMapping("/datasets")
    @Operation(summary = "数据集列表", description = "扫描 data/eval/datasets 目录，返回文件名与非空行数")
    public ApiResponse<Map<String, Object>> datasets() {
        return ApiResponse.ok(datasetService.listDatasets());
    }

    @GetMapping("/datasets/{name}/file")
    @Operation(summary = "数据集预览", description = "返回数据集前 20 行（逐行解析，坏行标记 valid=false）")
    @Parameter(name = "name", description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
    public ApiResponse<Map<String, Object>> datasetPreview(@PathVariable String name) {
        return ApiResponse.ok(datasetService.datasetPreview(name, 20));
    }

    @PostMapping("/datasets")
    @Operation(summary = "新建数据集", description = "创建空 jsonl 文件（同名已存在 409）")
    public ApiResponse<Map<String, Object>> createDataset(@Valid @RequestBody EvalDatasetDTO request) {
        return ApiResponse.ok(datasetService.createDataset(request.name()));
    }

    @GetMapping("/datasets/{name}/samples")
    @Operation(summary = "样本分页列表", description = "seq = 非空行序号（从 1 起），供编辑/删除定位；page 越界自动收敛")
    @Parameter(name = "name", description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
    public ApiResponse<Map<String, Object>> samples(@PathVariable String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(datasetService.datasetSamples(name, page, pageSize));
    }

    @PostMapping("/datasets/{name}/samples")
    @Operation(summary = "新增样本", description = "校验后追加一行（query 必填、评测器须已注册、id 唯一）")
    @Parameter(name = "name", description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
    public ApiResponse<Map<String, Object>> addSample(@PathVariable String name,
            @Valid @RequestBody EvalSampleDTO request) {
        return ApiResponse.ok(datasetService.addSample(name, request));
    }

    @PutMapping("/datasets/{name}/samples/{seq}")
    @Operation(summary = "编辑样本", description = "整读整写 + tmp 原子替换；seq 为非空行序号")
    @Parameter(name = "name", description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
    @Parameter(name = "seq", description = "非空行序号（从 1 起）", example = "1")
    public ApiResponse<Map<String, Object>> updateSample(@PathVariable String name, @PathVariable int seq,
            @Valid @RequestBody EvalSampleDTO request) {
        return ApiResponse.ok(datasetService.updateSample(name, seq, request));
    }

    @DeleteMapping("/datasets/{name}/samples/{seq}")
    @Operation(summary = "删除样本", description = "删除第 seq 个非空行（整读整写 + tmp 原子替换）")
    @Parameter(name = "name", description = "数据集文件名（如 demo.jsonl）", example = "demo.jsonl")
    @Parameter(name = "seq", description = "非空行序号（从 1 起）", example = "1")
    public ApiResponse<Map<String, Object>> deleteSample(@PathVariable String name, @PathVariable int seq) {
        return ApiResponse.ok(datasetService.deleteSample(name, seq));
    }

    @GetMapping("/samples/fromchat")
    @Operation(summary = "聊天记录沉淀", description = "提取指定会话第 msgIndex 条 AI 回答及其对应提问，"
            + "预填评测样本表单（不落盘；仅自己创建的会话，期望回答由人工补齐后走新增样本接口）")
    @Parameter(name = "sessionId", description = "会话 ID", example = "s-20260901-101530-abc")
    @Parameter(name = "msgIndex", description = "AI 回答在消息数组中的下标（从 0 起）", example = "5")
    public ApiResponse<FromChatSampleResultDTO> sampleFromChat(@RequestParam String sessionId,
            @RequestParam int msgIndex, HttpSession session) {
        SessionUser user = SessionUser.of(session);
        if (user == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        return ApiResponse.ok(datasetService.sampleFromChat(user.userId(), sessionId, msgIndex));
    }

    @GetMapping("/graders")
    @Operation(summary = "评测器清单", description = "当前已注册的评测器（名称/描述/类型 rule|llm）")
    public ApiResponse<Map<String, Object>> graders() {
        return ApiResponse.ok(customGraderService.graderList());
    }

    @GetMapping("/custom-graders")
    @Operation(summary = "自定义评测器清单", description = "用户创建的 LLM 评测器（附 mode/更新时间；"
            + "亦出现在 /graders 注册清单中，可勾选运行、被数据集引用、结果落盘可 Diff）")
    public ApiResponse<Map<String, Object>> customGraders() {
        return ApiResponse.ok(customGraderService.customGraderList());
    }

    @GetMapping("/custom-graders/{name}")
    @Operation(summary = "自定义评测器详情", description = "含 prompt 全文（编辑回填用；列表接口不含 prompt 控载荷）")
    @Parameter(name = "name", description = "评测器名", example = "my_concise_check")
    public ApiResponse<Map<String, Object>> customGraderDetail(@PathVariable String name) {
        return ApiResponse.ok(customGraderService.customGraderDetail(name));
    }

    @PostMapping("/custom-graders")
    @Operation(summary = "创建自定义评测器", description = "mode=criteria：prompt 仅写评分标准（样本内容框架隔离拼接）；"
            + "mode=template：prompt 为完整模板，占位符 {{query}}/{{answer}}/{{reference}}/{{context}}/{{rubric}}；"
            + "与内置评测器重名 400")
    public ApiResponse<Map<String, Object>> createCustomGrader(@Valid @RequestBody EvalCustomGraderDTO request) {
        return ApiResponse.ok(customGraderService.createCustomGrader(request));
    }

    @PutMapping("/custom-graders/{name}")
    @Operation(summary = "更新自定义评测器", description = "热替换注册表实例（同一次运行即生效）；名称不可改；"
            + "prompt 变更后历史 run 对比会标注「定义已变更」")
    @Parameter(name = "name", description = "评测器名（不可改）", example = "my_concise_check")
    public ApiResponse<Map<String, Object>> updateCustomGrader(@PathVariable String name,
            @Valid @RequestBody EvalCustomGraderDTO request) {
        return ApiResponse.ok(customGraderService.updateCustomGrader(name, request));
    }

    @DeleteMapping("/custom-graders/{name}")
    @Operation(summary = "删除自定义评测器", description = "被数据集内容引用时 409 并列出引用位置，不静默破坏")
    @Parameter(name = "name", description = "评测器名", example = "my_concise_check")
    public ApiResponse<Map<String, Object>> deleteCustomGrader(@PathVariable String name) {
        return ApiResponse.ok(customGraderService.deleteCustomGrader(name));
    }

    @PostMapping("/custom-graders/{name}/test")
    @Operation(summary = "试跑自定义评测器", description = "单次判分即时返回 score/reason（不落盘、不产生任务）；"
            + "先验证提示词有效再入库，避免整批跑完才发现评分细则写歪")
    @Parameter(name = "name", description = "评测器名", example = "my_concise_check")
    public ApiResponse<Map<String, Object>> testCustomGrader(@PathVariable String name,
            @Valid @RequestBody EvalCustomGraderTestDTO request) {
        return ApiResponse.ok(customGraderService.testCustomGrader(name, request));
    }

    @PostMapping("/runs")
    @Operation(summary = "创建评测任务", description = "异步执行，返回 taskId；同一时间仅允许一个任务（409）；"
            + "agent 未装配（未配置 OPENROUTER_API_KEY）时 409")
    public ApiResponse<Map<String, Object>> start(@Valid @RequestBody EvalRunDTO request,
            HttpSession session) {
        SessionUser user = SessionUser.of(session);
        if (user == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        List<String> graders = request.graders() == null ? List.of() : request.graders();
        return ApiResponse.ok(runService.start(request.dataset(), graders, request.judgeRuns(), user.userId()));
    }

    @GetMapping("/runs")
    @Operation(summary = "历史任务列表", description = "扫描结果目录（按时间倒序），含各任务汇总指标")
    public ApiResponse<Map<String, Object>> listRuns() {
        return ApiResponse.ok(runService.listRuns());
    }

    @GetMapping("/runs/diff")
    @Operation(summary = "回归对比（Run Diff）", description = "两次 run 的总体/按评测器/按样本三级对比 + 翻转矩阵；"
            + "先校验两 run 的 dataset 哈希（不一致提示数据集已变更，对比仅供参考）；"
            + "all=true 返回全部共同样本（默认仅返回有变化的样本，回归最严重置顶）")
    public ApiResponse<Map<String, Object>> diff(
            @Parameter(name = "base", description = "基线 run id（旧）", example = "eval-20260826-153000-a1b")
            @RequestParam String base,
            @Parameter(name = "target", description = "目标 run id（新）", example = "eval-20260828-100000-b2c")
            @RequestParam String target,
            @Parameter(name = "all", description = "true 返回全部共同样本；默认仅变化样本")
            @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.ok(runService.diff(base, target, all));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "任务进度/详情", description = "运行中返回实时进度；完成后（含服务重启后的历史）返回完整结果")
    @Parameter(name = "id", description = "任务 id（eval-时间-随机码）", example = "eval-20260826-153000-a1b")
    public ApiResponse<Map<String, Object>> progress(@PathVariable String id) {
        return ApiResponse.ok(runService.progress(id));
    }

    @PostMapping("/runs/{id}/humanscores")
    @Operation(summary = "人工评分", description = "对含 human_review 评测器的任务逐样本打分（1-5 星，可跳过）；"
            + "打分写回结果文件并重算汇总，全部样本评完任务状态翻转 success；可重复提交覆盖修改（留 grader/scoredAt 留痕）")
    @Parameter(name = "id", description = "任务 id（eval-时间-随机码）", example = "eval-20260902-100000-a1b")
    public ApiResponse<Map<String, Object>> humanScore(@PathVariable String id,
            @Valid @RequestBody EvalHumanScoreDTO request, HttpSession session) {
        SessionUser user = SessionUser.of(session);
        if (user == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        return ApiResponse.ok(runService.humanScore(id, request.sampleId(),
                request.rawScore(), request.comment(), request.skip(), user.userId()));
    }

    @GetMapping("/runs/{id}/file")
    @Operation(summary = "下载评测结果", description = "下载完整结果 JSON（样本明细 + 轨迹 + 各评测器得分；P6 起从库重建输出）")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        byte[] body = runService.exportResult(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @DeleteMapping("/runs/{id}")
    @Operation(summary = "删除评测记录", description = "按 id 删除历史评测结果文件（路径穿越双保险：RUN_ID_RE 正则 + normalize 后 startsWith 校验）")
    @Parameter(name = "id", description = "评测任务 id（文件名即 id，不含 .json 后缀）", example = "eval_20260917_103012_abc123")
    public ApiResponse<Map<String, Object>> deleteRun(@PathVariable String id, HttpSession session) {
        SessionUser user = SessionUser.of(session);
        if (user == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        if (!user.isAdmin()) {
            throw new ApiException(403, "需要管理员权限");
        }
        return ApiResponse.ok(runService.deleteRun(id, user));
    }
}
