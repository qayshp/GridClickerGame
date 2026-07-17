import { Router, type IRouter, type Request, type Response } from "express";
import { db, usersTable } from "@workspace/db";
import {
  GetPlayerGameDataParams,
  GetPlayerGameDataResponse,
  SavePlayerGameDataBody,
  SavePlayerGameDataResponse,
} from "@workspace/api-zod";
import { eq } from "drizzle-orm";

const router: IRouter = Router();

const DEFAULT_X = 15;
const DEFAULT_Y = 10;

router.get("/game/user/:userId", async (req: Request, res: Response) => {
  const params = GetPlayerGameDataParams.safeParse(req.params);
  if (!params.success) {
    res.status(400).json({ error: "Invalid userId" });
    return;
  }

  const [row] = await db
    .select({
      gameX: usersTable.gameX,
      gameY: usersTable.gameY,
      points: usersTable.points,
      upgrades: usersTable.upgrades,
    })
    .from(usersTable)
    .where(eq(usersTable.id, params.data.userId));

  res.json(
    GetPlayerGameDataResponse.parse({
      x: row?.gameX ?? DEFAULT_X,
      y: row?.gameY ?? DEFAULT_Y,
      points: row?.points ?? 0,
      upgrades: row?.upgrades ?? "",
    }),
  );
});

router.post("/game/position", async (req: Request, res: Response) => {
  const body = SavePlayerGameDataBody.safeParse(req.body);
  if (!body.success) {
    res.status(400).json({ error: "Invalid player state" });
    return;
  }
  const { userId, x, y, points, upgrades } = body.data;
  if (![x, y, points].every(Number.isSafeInteger)) {
    res.status(400).json({ error: "Invalid player state" });
    return;
  }

  await db
    .update(usersTable)
    .set({
      gameX: x,
      gameY: y,
      points,
      upgrades,
    })
    .where(eq(usersTable.id, userId));
  res.json(SavePlayerGameDataResponse.parse({ ok: true }));
});

export default router;
