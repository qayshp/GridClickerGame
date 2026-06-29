import { Router, type IRouter, type Request, type Response } from "express";
import { db, usersTable } from "@workspace/db";
import { eq } from "drizzle-orm";

const router: IRouter = Router();

const DEFAULT_X = 15;
const DEFAULT_Y = 10;

router.get("/game/user/:userId", async (req: Request, res: Response) => {
  const { userId } = req.params;
  const [row] = await db
    .select({ gameX: usersTable.gameX, gameY: usersTable.gameY, points: usersTable.points })
    .from(usersTable)
    .where(eq(usersTable.id, userId));

  res.json({
    x: row?.gameX ?? DEFAULT_X,
    y: row?.gameY ?? DEFAULT_Y,
    points: row?.points ?? 0,
  });
});

router.post("/game/position", async (req: Request, res: Response) => {
  const { userId, x, y, points } = req.body as {
    userId: string;
    x: number;
    y: number;
    points: number;
  };
  if (!userId || x == null || y == null) {
    res.status(400).json({ error: "Missing userId, x, or y" });
    return;
  }
  await db
    .update(usersTable)
    .set({ gameX: x, gameY: y, ...(points != null ? { points } : {}) })
    .where(eq(usersTable.id, userId));
  res.json({ ok: true });
});

export default router;
