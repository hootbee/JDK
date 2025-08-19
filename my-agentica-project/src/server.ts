import express, { Request, Response, NextFunction } from "express";
import { PublicDataService } from "./services/PublicDataService";
import dotenv from "dotenv";
import { DataDownloaderService } from "./services/DataDownloaderService";
import { DataAnalysisService } from "./services/DataAnalysisService";
import * as fs from "fs/promises";
import * as path from "path";

dotenv.config();

const app = express();
const port = process.env.PORT || 3001;
const publicDataService = new PublicDataService();

// 미들웨어 설정
app.use(express.json());

// CORS 설정
app.use((req: Request, res: Response, next: NextFunction) => {
  res.header("Access-Control-Allow-Origin", "*"); // 모든 출처 허용
  res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.header(
    "Access-Control-Allow-Headers",
    "Origin, X-Requested-With, Content-Type, Accept, Authorization"
  );
  if (req.method === "OPTIONS") {
    res.sendStatus(200);
  } else {
    next();
  }
});

// 에러 처리 유틸리티
function getErrorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

// ================================
// 🎯 AI 서비스 엔드포인트
// ================================

const downloaderService = new DataDownloaderService();
const analysisService = new DataAnalysisService(process.env.GEMINI_API_KEY!);

// 새로운 통합 분석 엔드포인트
app.post("/api/analyze-data-by-pk", async (req: Request, res: Response) => {
  const { publicDataPk } = req.body;
  if (!publicDataPk) {
    return res.status(400).json({
      error: "publicDataPk is required",
      code: "MISSING_PUBLIC_DATA_PK",
    });
  }

  const downloadsDir = path.resolve(__dirname, '../downloads');
  let downloadedFilePath: string | null = null;

  try {
    console.log(`[Workflow] 1. Downloading data for PK: ${publicDataPk}`);
    downloadedFilePath = await downloaderService.downloadDataFile(publicDataPk, downloadsDir);
    console.log(`[Workflow] File downloaded to: ${downloadedFilePath}`);

    if (!downloadedFilePath.toLowerCase().endsWith('.csv')) {
      console.log(`[Workflow] 2. File is not a CSV (${path.basename(downloadedFilePath)}). Deleting.`);
      await fs.unlink(downloadedFilePath);
      return res.status(200).json({ 
        message: "Downloaded file was not a CSV and has been deleted.",
        analysis: null 
      });
    }

    console.log(`[Workflow] 2. Analyzing CSV file: ${path.basename(downloadedFilePath)}`);
    const analysisResult = await analysisService.analyzeCsvFile(downloadedFilePath);
    
    console.log(`[Workflow] 3. Deleting analyzed file.`);
    await fs.unlink(downloadedFilePath);

    console.log("[Workflow] 4. Workflow completed successfully.");
    res.json({
      success: true,
      analysis: analysisResult,
    });

  } catch (error) {
    console.error("[Workflow] Error occurred:", error);
    // If a file was downloaded, try to clean it up even if the process failed.
    if (downloadedFilePath) {
      try {
        await fs.unlink(downloadedFilePath);
        console.log(`[Workflow] Cleaned up downloaded file due to error: ${path.basename(downloadedFilePath)}`);
      } catch (cleanupError) {
        console.error(`[Workflow] Failed to cleanup file after error: ${path.basename(downloadedFilePath)}`, cleanupError);
      }
    }
    res.status(500).json({
      error: "Failed to complete the analysis workflow",
      code: "WORKFLOW_ERROR",
      message: getErrorMessage(error),
    });
  }
});

// 전체 활용방안 (대시보드용)
app.post("/api/data-utilization/full", async (req: Request, res: Response) => {
  const { dataInfo } = req.body;
  if (!dataInfo) {
    return res.status(400).json({
      error: "dataInfo is required",
      code: "MISSING_DATA_INFO",
    });
  }
  try {
    console.log("📊 전체 활용방안 요청:", dataInfo.fileName);
    const result = await publicDataService.generateUtilizationRecommendations(
      dataInfo
    );
    res.json({
      success: true,
      data: result,
    });
  } catch (error) {
    console.error("전체 활용방안 생성 오류:", error);
    res.status(500).json({
      error: "Failed to generate full utilization recommendations",
      code: "UTILIZATION_ERROR",
      message: getErrorMessage(error),
    });
  }
});

// 단일 활용방안 (특정 카테고리)
app.post(
  "/api/data-utilization/single",
  async (req: Request, res: Response) => {
    const { dataInfo, analysisType } = req.body;
    if (!dataInfo || !analysisType) {
      return res.status(400).json({
        error: "dataInfo and analysisType are required",
        code: "MISSING_PARAMETERS",
      });
    }
    try {
      console.log(
        `📊 단일 활용방안 요청: ${dataInfo.fileName} (${analysisType})`
      );
      const result =
        await publicDataService.generateSingleUtilizationRecommendation({
          dataInfo,
          analysisType,
        });
      res.json(result); // 배열 직접 반환
    } catch (error) {
      console.error("단일 활용방안 생성 오류:", error);
      res.status(500).json(["오류가 발생했습니다: " + getErrorMessage(error)]);
    }
  }
);

// ================================
// 🩺 헬스 체크
// ================================

app.get("/health", (req: Request, res: Response) => {
  res.json({
    status: "healthy",
    timestamp: new Date().toISOString(),
    service: "Agentica AI Service",
  });
});

// 서버 시작
app.listen(port, () => {
  console.log(`🚀 Agentica AI Service running on http://localhost:${port}`);
  console.log(`📋 Available endpoints:`);
  console.log(`   POST /api/data-utilization/full - 전체 활용방안`);
  console.log(`   POST /api/data-utilization/single - 단일 활용방안`);
  console.log(`   GET  /health - 헬스 체크`);
});

export default app;
